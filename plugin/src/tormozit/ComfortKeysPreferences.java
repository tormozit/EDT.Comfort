package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.commands.Category;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.dialogs.FilteredTree;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.internal.keys.model.BindingElement;
import org.eclipse.ui.internal.keys.model.BindingModel;
import org.eclipse.ui.internal.keys.model.CommonModel;
import org.eclipse.ui.internal.keys.model.ConflictModel;
import org.eclipse.ui.internal.keys.model.KeyController;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.ui.internal.keys.BindingService;
import org.eclipse.ui.internal.keys.model.ModelElement;
import org.eclipse.ui.keys.IBindingService;

/**
 * Предфильтр страницы «Общие → Клавиши» по категории команд плагина.
 * Переход на страницу выполняется через {@link org.eclipse.ui.preferences.IWorkbenchPreferenceContainer#openPage}.
 */
public final class ComfortKeysPreferences
{
    /** Идентификатор страницы «Общие → Клавиши» ({@code org.eclipse.ui.preferencePages}). */
    public static final String KEYS_PREFERENCE_PAGE_ID =
            "org.eclipse.ui.preferencePages.Keys"; //$NON-NLS-1$

    /** Категория команд плагина ({@code plugin.xml}, {@code name="Комфорт"}). */
    public static final String COMMAND_CATEGORY_ID =
            "tormozit.commands.global"; //$NON-NLS-1$

    /**
     * Пояснение: дубли в Keys — runtime-зеркала; канон настройки — «В окнах» или Xtext для ИР.
     */
    public static final String GLOBAL_KEYS_HINT =
            "Глобальные команды (Перейти к определению, Копировать ссылку) — только «В окнах». "
            + "Команды ИР — только «Редактирование источника Xtext». Остальные контексты не редактируйте. "
            + "(U/CU — не отдельная настройка.)"; //$NON-NLS-1$

    private static final String FALLBACK_CATEGORY_NAME = "Комфорт"; //$NON-NLS-1$

    private static final String LOCAL_CONFLICT_UI_KEY =
            "tormozit.comfort.keys.localConflictUi"; //$NON-NLS-1$

    private static final String LOCAL_CONFLICT_TOOLBAR_KEY =
            "tormozit.comfort.keys.localConflictToolbar"; //$NON-NLS-1$

    private static final String LOCAL_CONFLICT_TABS_KEY =
            "tormozit.comfort.keys.localConflictTabs"; //$NON-NLS-1$

    private static final String HIDDEN_CONFLICT_HEADER_KEY =
            "tormozit.comfort.keys.hiddenConflictHeader"; //$NON-NLS-1$

    private static final String CONFLICT_TABLE_LAYOUT_KEY =
            "tormozit.comfort.keys.conflictTableLayout"; //$NON-NLS-1$

    private static final String CONFLICT_TABLE_RELAYOUT_KEY =
            "tormozit.comfort.keys.conflictTableRelayout"; //$NON-NLS-1$

    private static final String BINDINGS_TREE_COPY_KEY =
            "tormozit.comfort.keys.bindingsTreeCopy"; //$NON-NLS-1$

    private ComfortKeysPreferences()
    {
    }

    /**
     * Фильтр «Комфорт» на уже открытой странице «Клавиши»
     * (после {@code IWorkbenchPreferenceContainer.openPage}).
     */
    public static void scheduleApplyEnhancements(IPreferencePageContainer container)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> scheduleApplyEnhancementsDelayed(container, 0));
    }

    /**
     * Устанавливает фильтр «Комфорт» на странице «Клавиши» в отдельном диалоге.
     * Публичного API Eclipse для этого нет — используется рефлексия.
     */
    public static void applyCategoryFilterAsync()
    {
        PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(
            null,
            KEYS_PREFERENCE_PAGE_ID,
            null,
            null);
        scheduleCategoryFilter(dialog);
    }

    /** Подключает UI и опционально фильтр «Комфорт» (filterText=null — без фильтра). */
    public static void tryApplyEnhancements(IPreferencePage page, String filterText)
    {
        if (!isKeysPreferencePage(page))
            return;
        applyEnhancements(page, filterText);
    }

    static boolean isKeysPreferencePage(IPreferencePage page)
    {
        if (page == null)
            return false;
        String className = page.getClass().getName();
        if (className.contains("KeysPreferencePage")) //$NON-NLS-1$
            return true;
        return resolveKeyController(page) != null && resolveConflictViewer(page) != null;
    }

    static boolean isLocalConflictUiInstalled(IPreferencePage page)
    {
        TableViewer viewer = resolveConflictViewer(page);
        if (viewer == null)
            return false;
        Table table = viewer.getTable();
        return table != null && !table.isDisposed()
                && (table.getData(LOCAL_CONFLICT_UI_KEY) instanceof LocalConflictUi
                        || Boolean.TRUE.equals(table.getData(LOCAL_CONFLICT_TABS_KEY)));
    }

    private static void scheduleApplyEnhancementsDelayed(
            IPreferencePageContainer container, int attempt)
    {
        IPreferencePage page = findKeysPageFromContainer(container);
        if (page != null)
        {
            tryApplyEnhancements(page, resolveCategoryFilterText());
            if (isLocalConflictUiInstalled(page))
                return;
        }
        if (attempt >= 30)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () -> scheduleApplyEnhancementsDelayed(container, attempt + 1));
    }

    private static IPreferencePage findKeysPageFromContainer(IPreferencePageContainer container)
    {
        if (container == null)
            return null;
        try
        {
            Method getSelectedPage = container.getClass().getMethod(
                "getSelectedPage"); //$NON-NLS-1$
            Object page = getSelectedPage.invoke(container);
            if (page instanceof IPreferencePage preferencePage
                    && isKeysPreferencePage(preferencePage))
                return preferencePage;
        }
        catch (Exception ignored)
        {
            // контейнер без getSelectedPage — пробуем поля
        }
        try
        {
            for (Field field : container.getClass().getDeclaredFields())
            {
                field.setAccessible(true);
                Object value = field.get(container);
                if (value instanceof PreferenceDialog dialog)
                {
                    Object page = dialog.getSelectedPage();
                    if (page instanceof IPreferencePage preferencePage
                            && isKeysPreferencePage(preferencePage))
                        return preferencePage;
                }
            }
        }
        catch (Exception ignored)
        {
            // внутренняя разметка Eclipse изменилась
        }
        return null;
    }

    private static void scheduleCategoryFilter(PreferenceDialog dialog)
    {
        String filterText = resolveCategoryFilterText();
        Shell shell = dialog.getShell();
        if (shell == null || shell.isDisposed())
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            Object page = dialog.getSelectedPage();
            if (page instanceof IPreferencePage preferencePage)
                tryApplyEnhancements(preferencePage, filterText);
        });
    }

    private static String resolveCategoryFilterText()
    {
        ICommandService commandService =
            PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return FALLBACK_CATEGORY_NAME;
        Category category = commandService.getCategory(COMMAND_CATEGORY_ID);
        if (category == null)
            return FALLBACK_CATEGORY_NAME;
        try
        {
            String name = category.getName();
            if (name != null && !name.isBlank())
                return name;
        }
        catch (Exception ignored)
        {
            // категория ещё не определена в реестре команд
        }
        return FALLBACK_CATEGORY_NAME;
    }

    private static void applyEnhancements(IPreferencePage page, String filterText)
    {
        removeMirrorBindingHint(page);
        FilteredTree tree = resolveFilteredTree(page);
        if (filterText != null && tree != null)
            setFilterText(tree, filterText);
        wireBindingsTreeCopy(page);
        wireFilterHistory(tree);
        LocalConflictUi.install(page);
    }

    /** scopeId для {@link FilterHistoryStore} — отдельный от общего поиска по «Параметрам». */
    private static final String KEYS_FILTER_HISTORY_SCOPE = "keysFilter"; //$NON-NLS-1$

    private static final String KEYS_FILTER_HISTORY_WIRED_KEY =
            "tormozit.ComfortKeysPreferences.historyWired"; //$NON-NLS-1$

    /**
     * История значений фильтра страницы «Клавиши» — тот же приём (хранилище
     * + видимая кнопка + Ctrl+↓), что и в основном поиске по «Параметрам»
     * (см. {@link PreferenceSearchFilterAugmenter}), через общие
     * {@link FilterHistoryStore}/{@link FilterHistoryUi}. Идемпотентно —
     * {@code applyEnhancements} вызывается многократно poll-циклом хука, пока
     * страница окончательно не готова.
     */
    private static void wireFilterHistory(FilteredTree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        Text filterControl = tree.getFilterControl();
        if (filterControl == null || filterControl.isDisposed())
            return;
        if (Boolean.TRUE.equals(filterControl.getData(KEYS_FILTER_HISTORY_WIRED_KEY)))
            return;
        filterControl.setData(KEYS_FILTER_HISTORY_WIRED_KEY, Boolean.TRUE);

        FilterHistoryUi.wireKeyboard(filterControl, KEYS_FILTER_HISTORY_SCOPE);

        Composite buttonsRow = FilterHistoryUi.createButtonsRow(filterControl.getParent());
        FilterHistoryUi.addHistoryButton(buttonsRow, filterControl, KEYS_FILTER_HISTORY_SCOPE);
        if (buttonsRow != null)
            filterControl.getParent().layout(true, true);
    }

    private static FilteredTree resolveFilteredTree(IPreferencePage page)
    {
        Object tree = resolvePageField(page, "fFilteredTree"); //$NON-NLS-1$
        if (tree instanceof FilteredTree filteredTree)
            return filteredTree;
        return null;
    }

    private static KeyController resolveKeyController(IPreferencePage page)
    {
        Object value = resolvePageField(page, "keyController"); //$NON-NLS-1$
        if (value instanceof KeyController controller)
            return controller;
        return null;
    }

    private static TableViewer resolveConflictViewer(IPreferencePage page)
    {
        Object value = resolvePageField(page, "conflictViewer"); //$NON-NLS-1$
        if (value instanceof TableViewer viewer)
            return viewer;
        return null;
    }

    private static Object resolvePageField(IPreferencePage page, String fieldName)
    {
        if (page == null)
            return null;
        Class<?> type = page.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(page);
            }
            catch (Exception ignored)
            {
                // поле в суперклассе или переименовано
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static void removeMirrorBindingHint(IPreferencePage page)
    {
        FilteredTree tree = resolveFilteredTree(page);
        if (tree == null || tree.isDisposed())
            return;

        Composite parent = tree.getParent();
        if (parent == null || parent.isDisposed())
            return;

        for (Control child : parent.getChildren())
        {
            if (child.isDisposed() || !(child instanceof Label label))
                continue;
            if (GLOBAL_KEYS_HINT.equals(label.getText()))
                label.dispose();
        }
        parent.layout(true, true);
    }

    private static void setFilterText(FilteredTree tree, String text)
    {
        try
        {
            Method setFilterText = FilteredTree.class.getDeclaredMethod(
                "setFilterText", String.class); //$NON-NLS-1$
            setFilterText.setAccessible(true);
            setFilterText.invoke(tree, text);
            Method textChanged = FilteredTree.class.getDeclaredMethod("textChanged"); //$NON-NLS-1$
            textChanged.setAccessible(true);
            textChanged.invoke(tree);
        }
        catch (Exception ignored)
        {
            if (tree.getFilterControl() != null && !tree.getFilterControl().isDisposed())
                tree.getFilterControl().setText(text);
        }
    }

    private static void wireBindingsTreeCopy(IPreferencePage page)
    {
        FilteredTree filteredTree = resolveFilteredTree(page);
        if (filteredTree == null || filteredTree.isDisposed())
            return;

        TreeViewer viewer = filteredTree.getViewer();
        if (viewer == null)
            return;

        Tree swtTree = viewer.getTree();
        if (swtTree == null || swtTree.isDisposed())
            return;

        if (Boolean.TRUE.equals(swtTree.getData(BINDINGS_TREE_COPY_KEY)))
            return;

        swtTree.addListener(SWT.KeyDown, event -> {
            if ((event.stateMask & SWT.CTRL) == 0)
                return;
            if (event.character != 0x03 && event.keyCode != 'c' && event.keyCode != 'C') //$NON-NLS-1$
                return;
            if (copyBindingsTreeSelection(filteredTree))
                event.doit = false;
        });
        swtTree.setData(BINDINGS_TREE_COPY_KEY, Boolean.TRUE);
    }

    private static boolean copyBindingsTreeSelection(FilteredTree filteredTree)
    {
        if (filteredTree == null || filteredTree.isDisposed())
            return false;

        TreeViewer viewer = filteredTree.getViewer();
        if (viewer == null)
            return false;

        Tree swtTree = viewer.getTree();
        if (swtTree == null || swtTree.isDisposed())
            return false;

        ISelection selection = viewer.getSelection();
        if (!(selection instanceof IStructuredSelection structuredSelection)
                || structuredSelection.isEmpty()
                || !(structuredSelection.getFirstElement() instanceof BindingElement binding))
            return false;

        String text = formatBindingElementCopyText(viewer, swtTree, binding);

        Clipboard clipboard = new Clipboard(swtTree.getDisplay());
        try
        {
            clipboard.setContents(
                    new Object[] { text },
                    new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
        return true;
    }

    private static String formatBindingElementCopyText(
            TreeViewer viewer,
            Tree swtTree,
            BindingElement binding)
    {
        Object labelProvider = viewer.getLabelProvider();
        if (labelProvider instanceof ITableLabelProvider tableLabelProvider)
        {
            int columnCount = swtTree.getColumnCount();
            if (columnCount > 0)
            {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < columnCount; i++)
                {
                    if (i > 0)
                        sb.append('\t');
                    String columnText = tableLabelProvider.getColumnText(binding, i);
                    if (columnText != null)
                        sb.append(columnText);
                }
                return sb.toString();
            }
        }

        String command = binding.getName() != null ? binding.getName() : ""; //$NON-NLS-1$
        String sequence = ""; //$NON-NLS-1$
        if (binding.getTrigger() != null)
            sequence = binding.getTrigger().format();
        String context = ""; //$NON-NLS-1$
        if (binding.getContext() != null && binding.getContext().getName() != null)
            context = binding.getContext().getName();
        String category = binding.getCategory() != null ? binding.getCategory() : ""; //$NON-NLS-1$
        return command + '\t' + sequence + '\t' + context + '\t' + category;
    }

    /**
     * Автоанализ локальных пересечений: вкладки «Глобальные» / «Локальные» в блоке конфликтов Keys.
     */
    private static final class LocalConflictUi
    {
        private static final String ANALYSIS_JOB_NAME =
                "Анализ пересечений клавиш"; //$NON-NLS-1$

        private static final String TAB_RESOLVABLE_BASE = "Конфликты устранимые"; //$NON-NLS-1$

        private static final String TAB_UNRESOLVABLE_BASE = "Неустранимые"; //$NON-NLS-1$

        private static final String TAB_RESOLVABLE_TOOLTIP =
                "Конкуренты, чьё назначение можно изменить в Keys "
                + "(снять U или задать другую клавишу, в т.ч. вместо S)."; //$NON-NLS-1$

        private static final String TAB_UNRESOLVABLE_TOOLTIP =
                "Конкуренты, чьё назначение в Keys изменить нельзя — "
                + "остаётся только сменить клавишу у своей команды."; //$NON-NLS-1$

        private static final String COLUMN_COMMAND = "Команда"; //$NON-NLS-1$

        private static final String COLUMN_WHEN = "Когда"; //$NON-NLS-1$

        private static final int FALLBACK_VERTICAL_SCROLLBAR_WIDTH = 17;

        static void install(IPreferencePage page)
        {
            if (page == null)
                return;

            KeyController keyController = ComfortKeysPreferences.resolveKeyController(page);
            TableViewer conflictViewer = ComfortKeysPreferences.resolveConflictViewer(page);
            if (keyController == null || conflictViewer == null)
                return;

            Table table = conflictViewer.getTable();
            if (table == null || table.isDisposed())
                return;

            synchronized (table)
            {
                if (table.getData(LOCAL_CONFLICT_UI_KEY) instanceof LocalConflictUi)
                    return;
                if (Boolean.TRUE.equals(table.getData(LOCAL_CONFLICT_TABS_KEY)))
                    return;

                Composite parent = table.getParent();
                if (parent != null && !parent.isDisposed())
                    removeDuplicateToolbars(parent, table);

                LocalConflictUi hook = new LocalConflictUi(page, keyController, conflictViewer);
                hook.installUi(table);
                table.setData(LOCAL_CONFLICT_UI_KEY, hook);
            }
        }

        private static void removeDuplicateToolbars(Composite parent, Table table)
        {
            for (Control child : parent.getChildren())
            {
                if (child == table || child.isDisposed())
                    continue;
                if (!Boolean.TRUE.equals(child.getData(LOCAL_CONFLICT_TOOLBAR_KEY)))
                    continue;
                child.dispose();
            }
        }

        /** Скрывает подпись «Конфликты:» (не dispose — Eclipse обновляет её через lambda$11). */
        private void hideConflictsGroupHeader()
        {
            if (tabFolder == null || tabFolder.isDisposed())
                return;

            Composite tabParent = tabFolder.getParent();
            if (tabParent != null && !tabParent.isDisposed())
            {
                removeSpacingAboveTabFolder(tabParent, tabFolder);
                collapseRightDataAreaLayout(tabParent, tabFolder);
            }

            Composite rightDataArea = resolvePageComposite(page, "rightDataArea"); //$NON-NLS-1$
            if (rightDataArea != null && rightDataArea != tabParent)
            {
                removeSpacingAboveTabFolder(rightDataArea, tabFolder);
                collapseRightDataAreaLayout(rightDataArea, tabFolder);
            }

            tightenDataAreaLayout(page);

            Composite relayoutRoot = tabFolder.getParent();
            while (relayoutRoot != null && !(relayoutRoot instanceof Shell))
            {
                relayoutRoot.layout(true, true);
                relayoutRoot = relayoutRoot.getParent();
            }

            Display display = tabFolder.getDisplay();
            if (display != null && !display.isDisposed())
            {
                display.timerExec(0, () -> {
                    if (tabFolder == null || tabFolder.isDisposed())
                        return;
                    Composite area = tabFolder.getParent();
                    if (area != null && !area.isDisposed())
                    {
                        removeSpacingAboveTabFolder(area, tabFolder);
                        collapseRightDataAreaLayout(area, tabFolder);
                        area.layout(true, true);
                    }
                });
            }
        }

        private static void removeSpacingAboveTabFolder(Composite parent, Control tabFolder)
        {
            for (Control child : parent.getChildren())
            {
                if (child == tabFolder)
                    break;
                if (child.isDisposed())
                    continue;
                if (isEmptySpacingLabel(child))
                    disposeSpacingControl(child);
                else if (isStaticConflictsHeader(child))
                    hideControl(child);
            }
        }

        private static void collapseRightDataAreaLayout(Composite area, Control tabFolder)
        {
            if (area == null || area.isDisposed() || tabFolder == null || tabFolder.isDisposed())
                return;
            if (Boolean.TRUE.equals(area.getData(LOCAL_CONFLICT_TABS_KEY + ".collapsed"))) //$NON-NLS-1$
                return;

            for (Control child : area.getChildren())
            {
                if (child == tabFolder || child.isDisposed())
                    continue;
                if (isEmptySpacingLabel(child))
                    disposeSpacingControl(child);
                else if (isStaticConflictsHeader(child))
                    hideControl(child);
            }

            area.setLayout(tightGridLayout(1));
            GridData tabFolderGd = new GridData(SWT.FILL, SWT.FILL, true, true);
            tabFolderGd.verticalIndent = 0;
            tabFolderGd.horizontalIndent = 0;
            tabFolder.setLayoutData(tabFolderGd);
            area.setData(LOCAL_CONFLICT_TABS_KEY + ".collapsed", Boolean.TRUE); //$NON-NLS-1$
        }

        private static void disposeSpacingControl(Control control)
        {
            if (control == null || control.isDisposed())
                return;
            control.dispose();
        }

        private static Composite resolvePageComposite(IPreferencePage page, String fieldName)
        {
            Object value = ComfortKeysPreferences.resolvePageField(page, fieldName);
            if (value instanceof Composite composite)
                return composite;
            return null;
        }

        private static void hideConflictLabelDirectlyAbove(Composite parent, Control below)
        {
            removeSpacingAboveTabFolder(parent, below);
        }

        private static void hideRightDataAreaSpacing(Composite rightDataArea)
        {
            tightenCompositeGridLayout(rightDataArea);
        }

        private static void hideStaticConflictHeaderLabels(Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                if (child.isDisposed())
                    continue;
                if (child instanceof CTabFolder)
                    continue;
                if (isStaticConflictsHeader(child))
                    hideControl(child);
                else if (child instanceof Composite nested)
                    hideStaticConflictHeaderLabels(nested);
            }
        }

        private static void tightenCompositeGridLayout(Composite composite)
        {
            Object layout = composite.getLayout();
            if (!(layout instanceof GridLayout gridLayout))
                return;
            gridLayout.marginHeight = 0;
            gridLayout.marginTop = 0;
            gridLayout.verticalSpacing = 0;
            composite.setLayout(gridLayout);
        }

        private static boolean isEmptySpacingLabel(Control control)
        {
            if (!(control instanceof Label) && !(control instanceof CLabel))
                return false;
            String text = readControlText(control);
            return text == null || text.isEmpty();
        }

        private static void hideControl(Control control)
        {
            if (control == null || control.isDisposed())
                return;
            if (Boolean.TRUE.equals(control.getData(HIDDEN_CONFLICT_HEADER_KEY)))
                return;
            control.setData(HIDDEN_CONFLICT_HEADER_KEY, Boolean.TRUE);
            control.setVisible(false);
            GridData gd;
            if (control.getLayoutData() instanceof GridData existing)
                gd = existing;
            else
                gd = new GridData();
            gd.exclude = true;
            gd.heightHint = 0;
            gd.verticalIndent = 0;
            gd.horizontalIndent = 0;
            control.setLayoutData(gd);
        }

        private static void tightenDataAreaLayout(IPreferencePage page)
        {
            Composite dataArea = resolvePageComposite(page, "dataArea"); //$NON-NLS-1$
            if (dataArea == null || dataArea.isDisposed())
                return;

            Object layoutData = dataArea.getLayoutData();
            if (layoutData instanceof GridData gd)
            {
                gd.verticalIndent = 0;
                gd.verticalAlignment = SWT.FILL;
            }

            Composite rightDataArea = resolvePageComposite(page, "rightDataArea"); //$NON-NLS-1$
            if (rightDataArea != null && !rightDataArea.isDisposed())
            {
                tightenCompositeGridLayout(rightDataArea);
                Object rightLayoutData = rightDataArea.getLayoutData();
                if (rightLayoutData instanceof GridData rightGd)
                {
                    rightGd.verticalAlignment = SWT.FILL;
                    rightGd.verticalIndent = 0;
                    rightDataArea.setLayoutData(rightGd);
                }
            }
        }

        private static boolean isStaticConflictsHeader(Control control)
        {
            return isStaticConflictsHeaderText(readControlText(control));
        }

        private static boolean isStaticConflictsHeaderText(String text)
        {
            if (text == null)
                return false;
            String trimmed = text.strip().replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (trimmed.endsWith(":")) //$NON-NLS-1$
                trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
            String lower = trimmed.toLowerCase();
            return "conflicts".equals(lower) || "конфликты".equals(lower); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String readControlText(Control control)
        {
            if (control instanceof Label label)
                return label.getText();
            if (control instanceof CLabel clabel)
                return clabel.getText();
            return null;
        }

        private static GridData copyTableGridData(Table table)
        {
            Object layoutData = table.getLayoutData();
            if (layoutData instanceof GridData source)
            {
                GridData gd = new GridData(source.horizontalAlignment, source.verticalAlignment,
                        source.grabExcessHorizontalSpace, true,
                        source.horizontalSpan, source.verticalSpan);
                gd.minimumHeight = Math.max(80, source.minimumHeight);
                gd.widthHint = source.widthHint;
                gd.heightHint = source.heightHint;
                return gd;
            }
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            gd.minimumHeight = 80;
            return gd;
        }

        private static final int BINDING_VISIBILITY_MAX_ATTEMPTS = 60;
        private static final int BINDING_VISIBILITY_POLL_MS = 50;

        private static final class PendingConflictHighlight
        {
            final BindingElement source;
            final boolean localTab;

            PendingConflictHighlight(BindingElement source, boolean localTab)
            {
                this.source = source;
                this.localTab = localTab;
            }
        }

        private final IPreferencePage page;
        private final KeyController keyController;
        private final TableViewer conflictViewer;

        private CTabFolder tabFolder;
        private CTabItem globalTab;
        private CTabItem localTab;
        private TableViewer localConflictViewer;

        private List<ComfortKeysLocalConflictRow> globalRows = Collections.emptyList();
        private List<ComfortKeysLocalConflictRow> localRows = Collections.emptyList();

        private Job analysisJob;
        private int analysisGeneration;
        private boolean disposed;

        private PendingConflictHighlight pendingHighlight;

        private final IPropertyChangeListener keyControllerListener = this::onKeyControllerChange;

        private LocalConflictUi(
                IPreferencePage page,
                KeyController keyController,
                TableViewer conflictViewer)
        {
            this.page = page;
            this.keyController = keyController;
            this.conflictViewer = conflictViewer;
        }

        private void installUi(Table table)
        {
            Composite parent = table.getParent();
            if (parent == null || parent.isDisposed())
                return;

            GridData tabFolderGd = copyTableGridData(table);
            tabFolderGd.verticalIndent = 0;
            tabFolderGd.verticalAlignment = SWT.FILL;
            tabFolderGd.horizontalAlignment = SWT.FILL;
            tabFolderGd.grabExcessVerticalSpace = true;
            tabFolderGd.grabExcessHorizontalSpace = true;

            tabFolder = new CTabFolder(parent, SWT.FLAT);
            tabFolder.setLayoutData(tabFolderGd);
            tabFolder.setData(LOCAL_CONFLICT_TABS_KEY, Boolean.TRUE);
            tabFolder.moveAbove(table);

            globalTab = new CTabItem(tabFolder, SWT.NONE);
            globalTab.setToolTipText(TAB_RESOLVABLE_TOOLTIP);
            Composite globalComposite = new Composite(tabFolder, SWT.NONE);
            globalComposite.setLayout(tightGridLayout(1));
            globalTab.setControl(globalComposite);

            table.setParent(globalComposite);
            GridData tableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
            table.setLayoutData(tableGd);
            applyConflictTableColumnLayout(table);
            conflictViewer.setContentProvider(ArrayContentProvider.getInstance());
            conflictViewer.setLabelProvider(new LocalConflictLabelProvider());

            localTab = new CTabItem(tabFolder, SWT.NONE);
            localTab.setToolTipText(TAB_UNRESOLVABLE_TOOLTIP);
            Composite localComposite = new Composite(tabFolder, SWT.NONE);
            localComposite.setLayout(tightGridLayout(1));
            localTab.setControl(localComposite);
            localConflictViewer = createLocalConflictViewer(localComposite, table);
            wireLocalConflictViewer(localConflictViewer);
            wireGlobalConflictViewer(conflictViewer);

            tabFolder.setSelection(globalTab);
            tabFolder.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(
                    event -> scheduleConflictTablesRelayout()));

            hideConflictsGroupHeader();

            keyController.addPropertyChangeListener(keyControllerListener);

            table.addListener(SWT.Dispose, e -> dispose());
            tabFolder.addListener(SWT.Dispose, e -> dispose());

            refreshGlobalViewer();
            refreshLocalViewer();
            scheduleAutoAnalysis();

            parent.layout(true, true);
        }

        private TableViewer createLocalConflictViewer(Composite parent, Table referenceTable)
        {
            int style = SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER
                    | (referenceTable.getStyle() & SWT.V_SCROLL);
            Table localTable = new Table(parent, style);
            localTable.setHeaderVisible(true);
            localTable.setLinesVisible(referenceTable.getLinesVisible());
            localTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            new TableColumn(localTable, SWT.NONE).setText(COLUMN_COMMAND);
            new TableColumn(localTable, SWT.NONE).setText(COLUMN_WHEN);
            applyConflictTableColumnLayout(localTable);

            TableViewer viewer = new TableViewer(localTable);
            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.setLabelProvider(new LocalConflictLabelProvider());
            viewer.setInput(Collections.emptyList());
            return viewer;
        }

        private static GridLayout tightGridLayout(int columns)
        {
            GridLayout layout = new GridLayout(columns, false);
            layout.marginWidth = 0;
            layout.marginHeight = 0;
            layout.marginTop = 0;
            layout.verticalSpacing = 0;
            return layout;
        }

        /** Пропорция 60/40; резерв под вертикальный скролл — без горизонтального. */
        private static void applyConflictTableColumnLayout(Table table)
        {
            if (table == null || table.isDisposed() || table.getColumnCount() < 2)
                return;
            if (Boolean.TRUE.equals(table.getData(CONFLICT_TABLE_LAYOUT_KEY)))
                return;
            table.setData(CONFLICT_TABLE_LAYOUT_KEY, Boolean.TRUE);

            Listener relayout = event -> scheduleConflictTableRelayout(table);
            table.addListener(SWT.Resize, relayout);
            ScrollBar verticalBar = table.getVerticalBar();
            if (verticalBar != null)
                verticalBar.addListener(SWT.Show, relayout);

            scheduleConflictTableRelayout(table);
        }

        private void scheduleConflictTablesRelayout()
        {
            if (conflictViewer != null)
            {
                Table table = conflictViewer.getTable();
                if (table != null && !table.isDisposed())
                    scheduleConflictTableRelayout(table);
            }
            if (localConflictViewer != null)
            {
                Table table = localConflictViewer.getTable();
                if (table != null && !table.isDisposed())
                    scheduleConflictTableRelayout(table);
            }
        }

        private static void scheduleConflictTableRelayout(Table table)
        {
            if (table == null || table.isDisposed())
                return;
            Display display = table.getDisplay();
            if (display == null || display.isDisposed())
                return;
            if (Boolean.TRUE.equals(table.getData(CONFLICT_TABLE_RELAYOUT_KEY)))
                return;
            table.setData(CONFLICT_TABLE_RELAYOUT_KEY, Boolean.TRUE);
            display.timerExec(0, () -> {
                if (table.isDisposed())
                    return;
                table.setData(CONFLICT_TABLE_RELAYOUT_KEY, null);
                relayoutConflictTableColumns(table);
            });
        }

        private static void relayoutConflictTableColumns(Table table)
        {
            if (table.isDisposed() || table.getColumnCount() < 2)
                return;

            int width = table.getClientArea().width;
            if (width <= 0)
                return;

            width = Math.max(0, width - resolveVerticalScrollReserve(table));
            TableColumn first = table.getColumn(0);
            TableColumn second = table.getColumn(1);
            int firstWidth = Math.max(60, width * 60 / 100);
            int secondWidth = Math.max(60, width - firstWidth);
            if (firstWidth + secondWidth > width)
                secondWidth = Math.max(60, width - firstWidth);

            first.setWidth(firstWidth);
            second.setWidth(secondWidth);
        }

        private static int resolveVerticalScrollReserve(Table table)
        {
            ScrollBar verticalBar = table.getVerticalBar();
            if (verticalBar != null && verticalBar.isVisible() && verticalBar.getSize().x > 0)
                return verticalBar.getSize().x;

            int itemCount = table.getItemCount();
            if (itemCount <= 0)
                return 0;

            int rowHeight = table.getItemHeight() + (table.getLinesVisible() ? 1 : 0);
            int headerHeight = table.getHeaderVisible() ? table.getHeaderHeight() : 0;
            int contentHeight = headerHeight + itemCount * rowHeight;
            int availableHeight = table.getClientArea().height;
            if (contentHeight <= availableHeight)
                return 0;

            if (verticalBar != null && verticalBar.getSize().x > 0)
                return verticalBar.getSize().x;

            return FALLBACK_VERTICAL_SCROLLBAR_WIDTH;
        }

        private void wireLocalConflictViewer(TableViewer viewer)
        {
            Table table = viewer.getTable();
            if (table == null)
                return;

            table.addListener(SWT.MouseDown, event -> {
                if (event.button != 1 || table.isDisposed())
                    return;
                TableItem item = table.getItem(new Point(event.x, event.y));
                if (item == null)
                    return;
                Object data = item.getData();
                if (data == null)
                    return;
                viewer.setSelection(new StructuredSelection(data));
                table.setFocus();
            });

            table.addListener(SWT.KeyDown, event -> {
                if ((event.stateMask & SWT.CTRL) == 0)
                    return;
                if (event.character != 0x03 && event.keyCode != 'c' && event.keyCode != 'C') //$NON-NLS-1$
                    return;
                if (copyLocalConflictSelection(viewer))
                    event.doit = false;
            });

            viewer.addDoubleClickListener((IDoubleClickListener) event -> {
                ComfortKeysLocalConflictRow row = getSelectedLocalConflictRow(viewer);
                if (row == null)
                    return;
                BindingElement target = resolveLocalConflictBinding(row);
                navigateFromConflictDoubleClick(target, getSelectedBinding(), true);
            });
        }

        private static void stripConflictViewerNavigationListeners(TableViewer viewer)
        {
            try
            {
                Field field = Viewer.class.getDeclaredField("selectionChangedListeners"); //$NON-NLS-1$
                field.setAccessible(true);
                Object list = field.get(viewer);
                if (list == null)
                    return;
                Method clear = list.getClass().getMethod("clear"); //$NON-NLS-1$
                clear.invoke(list);
            }
            catch (Exception ignored)
            {
                // внутренний API Eclipse изменился
            }
        }

        private void wireGlobalConflictViewer(TableViewer viewer)
        {
            Table table = viewer.getTable();
            if (table == null)
                return;

            stripConflictViewerNavigationListeners(viewer);

            table.addListener(SWT.MouseDown, event -> {
                if (event.button != 1 || table.isDisposed())
                    return;
                TableItem item = table.getItem(new Point(event.x, event.y));
                if (item == null)
                    return;
                Object data = item.getData();
                if (data == null)
                    return;
                viewer.setSelection(new StructuredSelection(data));
                table.setFocus();
            });

            table.addListener(SWT.KeyDown, event -> {
                if ((event.stateMask & SWT.CTRL) == 0)
                    return;
                if (event.character != 0x03 && event.keyCode != 'c' && event.keyCode != 'C') //$NON-NLS-1$
                    return;
                if (copyGlobalConflictSelection(viewer))
                    event.doit = false;
            });

            viewer.addDoubleClickListener((IDoubleClickListener) event -> {
                ComfortKeysLocalConflictRow row = getSelectedGlobalConflictRow(viewer);
                if (row == null)
                    return;
                BindingElement target = resolveLocalConflictBinding(row);
                navigateFromConflictDoubleClick(target, getSelectedBinding(), false);
            });
        }

        private ComfortKeysLocalConflictRow getSelectedLocalConflictRow(TableViewer viewer)
        {
            ISelection selection = viewer.getSelection();
            if (!(selection instanceof IStructuredSelection structuredSelection)
                    || structuredSelection.isEmpty())
                return null;
            Object element = structuredSelection.getFirstElement();
            if (element instanceof ComfortKeysLocalConflictRow row)
                return row;
            return null;
        }

        private boolean copyLocalConflictSelection(TableViewer viewer)
        {
            ComfortKeysLocalConflictRow row = getSelectedLocalConflictRow(viewer);
            if (row == null)
                return false;

            Table table = viewer.getTable();
            if (table == null || table.isDisposed())
                return false;

            Clipboard clipboard = new Clipboard(table.getDisplay());
            try
            {
                clipboard.setContents(
                        new Object[] { row.copyText() },
                        new Transfer[] { TextTransfer.getInstance() });
            }
            finally
            {
                clipboard.dispose();
            }
            return true;
        }

        private ComfortKeysLocalConflictRow getSelectedGlobalConflictRow(TableViewer viewer)
        {
            ISelection selection = viewer.getSelection();
            if (!(selection instanceof IStructuredSelection structuredSelection)
                    || structuredSelection.isEmpty())
                return null;
            Object element = structuredSelection.getFirstElement();
            if (element instanceof ComfortKeysLocalConflictRow row)
                return row;
            return null;
        }

        private boolean copyGlobalConflictSelection(TableViewer viewer)
        {
            ComfortKeysLocalConflictRow row = getSelectedGlobalConflictRow(viewer);
            if (row == null)
                return false;

            Table table = viewer.getTable();
            if (table == null || table.isDisposed())
                return false;

            Clipboard clipboard = new Clipboard(table.getDisplay());
            try
            {
                clipboard.setContents(
                        new Object[] { row.copyText() },
                        new Transfer[] { TextTransfer.getInstance() });
            }
            finally
            {
                clipboard.dispose();
            }
            return true;
        }

        private void navigateFromConflictDoubleClick(
                BindingElement target,
                BindingElement source,
                boolean localTab)
        {
            if (target == null || source == null || sameBindingElement(target, source))
                return;

            pendingHighlight = new PendingConflictHighlight(source, localTab);
            navigateToBindingWithFilterWait(target, null);
        }

        private void navigateToBindingWithFilterWait(BindingElement binding, Runnable onDone)
        {
            if (binding == null)
                return;

            FilteredTree filteredTree = ComfortKeysPreferences.resolveFilteredTree(page);
            if (filteredTree == null || filteredTree.isDisposed())
            {
                BindingModel bindingModel = keyController.getBindingModel();
                bindingModel.setSelectedElement(binding);
                if (onDone != null)
                    onDone.run();
                return;
            }

            if (!isBindingVisibleInTree(filteredTree, binding))
            {
                ComfortKeysPreferences.setFilterText(filteredTree, ""); //$NON-NLS-1$
                TreeViewer viewer = filteredTree.getViewer();
                viewer.refresh();
                viewer.reveal(binding);
                waitForBindingVisible(filteredTree, binding, 0, () -> {
                    selectBindingInTree(filteredTree, binding);
                    if (onDone != null)
                        onDone.run();
                });
                return;
            }

            selectBindingInTree(filteredTree, binding);
            if (onDone != null)
                onDone.run();
        }

        private void waitForBindingVisible(
                FilteredTree filteredTree,
                BindingElement binding,
                int attempt,
                Runnable onDone)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed() || disposed)
                return;

            if (isBindingVisibleInTree(filteredTree, binding) || attempt >= BINDING_VISIBILITY_MAX_ATTEMPTS)
            {
                onDone.run();
                return;
            }

            display.timerExec(BINDING_VISIBILITY_POLL_MS, () -> {
                if (disposed || filteredTree.isDisposed())
                    return;
                waitForBindingVisible(filteredTree, binding, attempt + 1, onDone);
            });
        }

        private void selectBindingInTree(FilteredTree filteredTree, BindingElement binding)
        {
            BindingModel bindingModel = keyController.getBindingModel();
            bindingModel.setSelectedElement(binding);
            TreeViewer viewer = filteredTree.getViewer();
            viewer.reveal(binding);
            viewer.setSelection(new StructuredSelection(binding), true);
        }

        private void applyPendingConflictHighlight()
        {
            if (disposed || pendingHighlight == null)
                return;

            PendingConflictHighlight highlight = pendingHighlight;
            BindingElement source = highlight.source;

            if (highlight.localTab)
            {
                ComfortKeysLocalConflictRow row = findLocalRowForBinding(source);
                if (row == null || localConflictViewer == null)
                    return;

                localConflictViewer.setSelection(new StructuredSelection(row));
                Table localTable = localConflictViewer.getTable();
                if (localTable != null && !localTable.isDisposed())
                    localTable.setFocus();
                pendingHighlight = null;
                return;
            }

            ComfortKeysLocalConflictRow row = findGlobalRowForBinding(source);
            if (row == null)
                return;

            conflictViewer.setSelection(new StructuredSelection(row));
            Table table = conflictViewer.getTable();
            if (table != null && !table.isDisposed())
            {
                table.setFocus();
                showSelectedConflictRow(conflictViewer);
            }
            pendingHighlight = null;
        }

        private static void showSelectedConflictRow(TableViewer viewer)
        {
            Table table = viewer.getTable();
            if (table == null || table.isDisposed())
                return;

            ISelection selection = viewer.getSelection();
            if (!(selection instanceof IStructuredSelection structuredSelection)
                    || structuredSelection.isEmpty())
                return;

            Object element = structuredSelection.getFirstElement();
            for (TableItem item : table.getItems())
            {
                if (element.equals(item.getData()))
                {
                    table.showItem(item);
                    return;
                }
            }
        }

        private ComfortKeysLocalConflictRow findGlobalRowMatching(ComfortKeysLocalConflictRow pattern)
        {
            if (pattern == null)
                return null;

            for (ComfortKeysLocalConflictRow row : globalRows)
            {
                if (!pattern.commandId.equals(row.commandId))
                    continue;
                if (pattern.contextId == null
                        ? row.contextId != null
                        : !pattern.contextId.equals(row.contextId))
                    continue;
                if (pattern.bindingType == row.bindingType)
                    return row;
            }

            for (ComfortKeysLocalConflictRow row : globalRows)
            {
                if (!pattern.commandId.equals(row.commandId))
                    continue;
                if (pattern.contextId == null
                        ? row.contextId != null
                        : !pattern.contextId.equals(row.contextId))
                    continue;
                return row;
            }
            return null;
        }

        private ComfortKeysLocalConflictRow findLocalRowMatching(ComfortKeysLocalConflictRow pattern)
        {
            if (pattern == null)
                return null;

            for (ComfortKeysLocalConflictRow row : localRows)
            {
                if (!pattern.commandId.equals(row.commandId))
                    continue;
                if (pattern.contextId == null
                        ? row.contextId != null
                        : !pattern.contextId.equals(row.contextId))
                    continue;
                if (pattern.bindingType == row.bindingType)
                    return row;
            }

            for (ComfortKeysLocalConflictRow row : localRows)
            {
                if (!pattern.commandId.equals(row.commandId))
                    continue;
                if (pattern.contextId == null
                        ? row.contextId != null
                        : !pattern.contextId.equals(row.contextId))
                    continue;
                return row;
            }
            return null;
        }

        private ComfortKeysLocalConflictRow findLocalRowForBinding(BindingElement source)
        {
            if (source == null)
                return null;

            ComfortKeysLocalConflictRow strict = findLocalRowForBinding(source, true);
            if (strict != null)
                return strict;
            return findLocalRowForBinding(source, false);
        }

        private ComfortKeysLocalConflictRow findLocalRowForBinding(
                BindingElement source,
                boolean requireBindingType)
        {
            String commandId = source.getId();
            String contextId = resolveBindingElementContextId(source);
            for (ComfortKeysLocalConflictRow row : localRows)
            {
                if (commandId == null || !commandId.equals(row.commandId))
                    continue;
                if (contextId == null ? row.contextId != null : !contextId.equals(row.contextId))
                    continue;
                if (requireBindingType && !matchesBindingType(source, row.bindingType))
                    continue;
                return row;
            }
            return null;
        }

        private ComfortKeysLocalConflictRow findGlobalRowForBinding(BindingElement source)
        {
            if (source == null)
                return null;

            ComfortKeysLocalConflictRow strict = findGlobalRowForBinding(source, true);
            if (strict != null)
                return strict;
            return findGlobalRowForBinding(source, false);
        }

        private ComfortKeysLocalConflictRow findGlobalRowForBinding(
                BindingElement source,
                boolean requireBindingType)
        {
            for (ComfortKeysLocalConflictRow row : globalRows)
            {
                if (row.bindingElement != null
                        && sameBindingElement(source, row.bindingElement))
                    return row;

                String commandId = source.getId();
                String contextId = resolveBindingElementContextId(source);
                if (commandId == null || !commandId.equals(row.commandId))
                    continue;
                if (contextId == null ? row.contextId != null : !contextId.equals(row.contextId))
                    continue;
                if (requireBindingType && !matchesBindingType(source, row.bindingType))
                    continue;
                return row;
            }
            return null;
        }

        private static boolean sameBindingElement(BindingElement a, BindingElement b)
        {
            if (a == null || b == null)
                return a == b;
            if (!java.util.Objects.equals(a.getId(), b.getId()))
                return false;
            if (!java.util.Objects.equals(resolveBindingElementContextId(a), resolveBindingElementContextId(b)))
                return false;
            int typeA = a.getUserDelta() != null
                    ? a.getUserDelta().intValue()
                    : org.eclipse.jface.bindings.Binding.SYSTEM;
            int typeB = b.getUserDelta() != null
                    ? b.getUserDelta().intValue()
                    : org.eclipse.jface.bindings.Binding.SYSTEM;
            return typeA == typeB;
        }

        private BindingElement resolveLocalConflictBinding(ComfortKeysLocalConflictRow row)
        {
            if (row == null)
                return null;
            BindingElement binding = row.bindingElement;
            if (binding == null)
                binding = findBindingElement(row);
            return binding;
        }

        private BindingElement findBindingElement(ComfortKeysLocalConflictRow row)
        {
            for (Object element : keyController.getBindingModel().getBindings())
            {
                if (!(element instanceof BindingElement bindingElement))
                    continue;
                if (!row.commandId.equals(bindingElement.getId()))
                    continue;
                String contextId = resolveBindingElementContextId(bindingElement);
                if (!row.contextId.equals(contextId))
                    continue;
                if (!matchesBindingType(bindingElement, row.bindingType))
                    continue;
                return bindingElement;
            }
            return null;
        }

        private static String resolveBindingElementContextId(BindingElement bindingElement)
        {
            if (bindingElement.getContext() != null)
            {
                String id = bindingElement.getContext().getId();
                if (id != null && !id.isBlank())
                    return id;
            }
            try
            {
                Object id = Global.invoke(bindingElement, "getContextId"); //$NON-NLS-1$
                if (id instanceof String contextId && !contextId.isBlank())
                    return contextId;
            }
            catch (Exception ignored)
            {
                // package-private getContextId
            }
            return null;
        }

        private static boolean matchesBindingType(BindingElement bindingElement, int bindingType)
        {
            Integer userDelta = bindingElement.getUserDelta();
            if (userDelta != null)
                return userDelta.intValue() == bindingType;
            return bindingType == org.eclipse.jface.bindings.Binding.SYSTEM;
        }

        private static boolean isBindingVisibleInTree(FilteredTree filteredTree, BindingElement binding)
        {
            for (TreeItem item : filteredTree.getViewer().getTree().getItems())
            {
                if (treeItemMatchesBinding(item, binding))
                    return true;
            }
            return false;
        }

        private static boolean treeItemMatchesBinding(TreeItem item, BindingElement binding)
        {
            Object data = item.getData();
            if (binding.equals(data))
                return true;
            if (data instanceof BindingElement treeBinding
                    && sameBindingElement(binding, treeBinding))
                return true;

            for (TreeItem child : item.getItems())
            {
                if (treeItemMatchesBinding(child, binding))
                    return true;
            }
            return false;
        }

        private void onKeyControllerChange(PropertyChangeEvent event)
        {
            if (disposed)
                return;

            Object source = event.getSource();
            String property = event.getProperty();

            if (source == keyController.getConflictModel())
            {
                if (ConflictModel.PROP_CONFLICTS.equals(property)
                        || ConflictModel.PROP_CONFLICTS_ADD.equals(property)
                        || ConflictModel.PROP_CONFLICTS_REMOVE.equals(property))
                {
                    scheduleRefreshFromEclipse();
                }
            }
            else if (source == keyController.getBindingModel()
                    && CommonModel.PROP_SELECTED_ELEMENT.equals(property))
            {
                scheduleAutoAnalysis();
            }
            else if (source instanceof BindingElement bindingElement
                    && BindingElement.PROP_TRIGGER.equals(property)
                    && bindingElement == getSelectedBinding())
            {
                scheduleAutoAnalysis();
            }
        }

        private void scheduleRefreshFromEclipse()
        {
            scheduleAutoAnalysis();
        }

        private void clearAnalysisRows()
        {
            globalRows = Collections.emptyList();
            localRows = Collections.emptyList();
            refreshGlobalViewer();
            refreshLocalViewer();
        }

        private void refreshGlobalViewer()
        {
            if (disposed || conflictViewer == null)
                return;
            Table table = conflictViewer.getTable();
            if (table == null || table.isDisposed())
                return;

            ComfortKeysLocalConflictRow restoreRow = null;
            if (pendingHighlight != null && !pendingHighlight.localTab)
                restoreRow = findGlobalRowForBinding(pendingHighlight.source);
            else
            {
                ComfortKeysLocalConflictRow selected = getSelectedGlobalConflictRow(conflictViewer);
                if (selected != null)
                    restoreRow = selected;
            }

            conflictViewer.setInput(globalRows);
            updateTabTitles();
            scheduleConflictTablesRelayout();
            if (pendingHighlight != null && !pendingHighlight.localTab)
            {
                applyPendingConflictHighlight();
                return;
            }

            if (restoreRow != null)
            {
                ComfortKeysLocalConflictRow row = findGlobalRowMatching(restoreRow);
                if (row != null)
                {
                    conflictViewer.setSelection(new StructuredSelection(row));
                    showSelectedConflictRow(conflictViewer);
                }
            }
        }

        private void refreshLocalViewer()
        {
            if (disposed || localConflictViewer == null)
                return;
            Table localTable = localConflictViewer.getTable();
            if (localTable == null || localTable.isDisposed())
                return;

            ComfortKeysLocalConflictRow restoreRow = null;
            if (pendingHighlight != null && pendingHighlight.localTab)
                restoreRow = findLocalRowForBinding(pendingHighlight.source);
            else
            {
                ComfortKeysLocalConflictRow selected = getSelectedLocalConflictRow(localConflictViewer);
                if (selected != null)
                    restoreRow = selected;
            }

            localConflictViewer.setInput(localRows);
            updateTabTitles();
            scheduleConflictTablesRelayout();
            if (pendingHighlight != null && pendingHighlight.localTab)
            {
                applyPendingConflictHighlight();
                return;
            }

            if (restoreRow != null)
            {
                ComfortKeysLocalConflictRow row = findLocalRowMatching(restoreRow);
                if (row != null)
                {
                    localConflictViewer.setSelection(new StructuredSelection(row));
                    showSelectedConflictRow(localConflictViewer);
                }
            }
        }

        private void updateTabTitles()
        {
            if (tabFolder == null || tabFolder.isDisposed())
                return;
            if (globalTab != null && !globalTab.isDisposed())
                globalTab.setText(TAB_RESOLVABLE_BASE + " (" + globalRows.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            if (localTab != null && !localTab.isDisposed())
                localTab.setText(TAB_UNRESOLVABLE_BASE + " (" + localRows.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void scheduleAutoAnalysis()
        {
            if (disposed)
                return;

            BindingElement selected = getSelectedBinding();
            if (!ComfortKeysLocalConflictAnalyzer.hasAssignedKey(selected))
            {
                if (analysisJob != null)
                {
                    analysisJob.cancel();
                    analysisJob = null;
                }
                if (pendingHighlight == null)
                    clearAnalysisRows();
                return;
            }

            if (analysisJob != null)
                analysisJob.cancel();

            analysisGeneration++;
            final int generation = analysisGeneration;
            final BindingElement selectedBinding = selected;
            analysisJob = new Job(ANALYSIS_JOB_NAME)
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    ComfortKeysAnalysisResult result =
                            ComfortKeysLocalConflictAnalyzer.analyze(
                                    keyController, selectedBinding, monitor);

                    Display display = Display.getDefault();
                    if (display == null || display.isDisposed())
                        return Status.CANCEL_STATUS;

                    display.asyncExec(() -> {
                        if (disposed)
                            return;
                        if (generation != analysisGeneration)
                            return;
                        if (getSelectedBinding() != selectedBinding)
                            return;
                        globalRows = result.globalRows;
                        localRows = result.localRows;
                        refreshGlobalViewer();
                        refreshLocalViewer();
                    });
                    return monitor.isCanceled()
                            ? Status.CANCEL_STATUS
                            : Status.OK_STATUS;
                }
            };
            analysisJob.setSystem(true);
            analysisJob.setUser(false);
            analysisJob.schedule();
        }

        private BindingElement getSelectedBinding()
        {
            Object selected = keyController.getBindingModel().getSelectedElement();
            if (selected instanceof BindingElement bindingElement)
                return bindingElement;
            return null;
        }

        private void dispose()
        {
            if (disposed)
                return;
            disposed = true;
            pendingHighlight = null;
            if (analysisJob != null)
                analysisJob.cancel();
            keyController.removePropertyChangeListener(keyControllerListener);
            Table table = conflictViewer.getTable();
            if (table != null && !table.isDisposed())
                table.setData(LOCAL_CONFLICT_UI_KEY, null);
            if (tabFolder != null && !tabFolder.isDisposed())
                tabFolder.setData(LOCAL_CONFLICT_TABS_KEY, null);
        }
    }

    private static final class LocalConflictLabelProvider implements ITableLabelProvider
    {
        @Override
        public Image getColumnImage(Object element, int columnIndex)
        {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex)
        {
            if (!(element instanceof ComfortKeysLocalConflictRow row))
                return ""; //$NON-NLS-1$
            return columnIndex == 0
                    ? row.commandColumnText()
                    : row.contextColumnText();
        }

        @Override
        public void addListener(org.eclipse.jface.viewers.ILabelProviderListener listener)
        {
            // read-only provider
        }

        @Override
        public void dispose()
        {
            // nothing
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return false;
        }

        @Override
        public void removeListener(org.eclipse.jface.viewers.ILabelProviderListener listener)
        {
            // read-only provider
        }
    }

    /**
     * Анализ пересечений клавиш на странице «Клавиши».
     * <p>
     * Источник — каталог {@link KeyController} / BindingManager, не runtime
     * {@link BindingService#getBindings()} (там хуки могут снимать привязки).
     * <p>
     * «Конфликты устранимые»: назначение конкурента пользователь может изменить в Keys
     * (снять U или задать другую клавишу, в т.ч. вместо S).
     * «Конфликты неустранимые»: назначение конкурента в Keys изменить нельзя.
     */
    private static final class ComfortKeysLocalConflictAnalyzer
    {
        private static final String TAG = "ComfortKeysLocalConflict"; //$NON-NLS-1$

        private static final String DEFAULT_SCHEME_ID =
                "org.eclipse.ui.defaultAcceleratorConfiguration"; //$NON-NLS-1$

        private static final String DESIGNER_SCHEME_ID =
                "com._1c.g5.v8.designer.scheme"; //$NON-NLS-1$

        private ComfortKeysLocalConflictAnalyzer()
        {
        }

        static boolean hasAssignedKey(BindingElement selected)
        {
            if (selected == null)
                return false;
            TriggerSequence trigger = selected.getTrigger();
            return trigger != null && !trigger.isEmpty();
        }

        static ComfortKeysAnalysisResult analyze(
                KeyController keyController,
                BindingElement selected,
                IProgressMonitor monitor)
        {
            if (keyController == null || selected == null)
                return ComfortKeysAnalysisResult.EMPTY;

            if (!hasAssignedKey(selected))
                return ComfortKeysAnalysisResult.EMPTY;

            TriggerSequence trigger = selected.getTrigger();
            if (!(trigger instanceof KeySequence targetSequence))
                return ComfortKeysAnalysisResult.EMPTY;

            BindingService bindingService = resolveBindingService(keyController);
            String activeScheme = resolveActiveScheme(keyController, bindingService);
            String platform = bindingService != null ? bindingService.getPlatform() : null;
            String locale = bindingService != null ? bindingService.getLocale() : null;
            ICommandService commandService =
                    PlatformUI.getWorkbench().getService(ICommandService.class);

            Binding[] bindings = resolveManagerBindings(keyController);
            ScanContext ctx = new ScanContext(
                    keyController,
                    selected,
                    targetSequence,
                    activeScheme,
                    platform,
                    locale,
                    commandService,
                    bindings,
                    monitor);

            List<ComfortKeysLocalConflictRow> globalRows = scanResolvableSameKey(ctx);
            List<ComfortKeysLocalConflictRow> localRows = scanUnresolvableSameKey(ctx);

            if (Global.isLogEnabled())
            {
                Global.log(TAG, "analyze seq=" + targetSequence.format() //$NON-NLS-1$
                        + " scheme=" + activeScheme //$NON-NLS-1$
                        + " catalog=" + bindings.length //$NON-NLS-1$
                        + " resolvable=" + globalRows.size() //$NON-NLS-1$
                        + " unresolvable=" + localRows.size()); //$NON-NLS-1$
            }

            return new ComfortKeysAnalysisResult(globalRows, localRows);
        }

        private static List<ComfortKeysLocalConflictRow> scanResolvableSameKey(ScanContext ctx)
        {
            Map<String, ComfortKeysLocalConflictRow> byCommandContext = new LinkedHashMap<>();
            for (int i = 0; i < ctx.bindings.length; i++)
            {
                if (ctx.monitor != null && ctx.monitor.isCanceled())
                    break;
                if (ctx.monitor != null && (i % 50 == 0))
                    ctx.monitor.worked(1);

                Binding binding = ctx.bindings[i];
                if (!matchesCatalogBinding(ctx, binding))
                    continue;

                if (!isCompetitorAssignmentUserChangeable(
                        ctx.keyController, binding, ctx.commandService))
                    continue;

                addRow(
                        byCommandContext,
                        ctx,
                        binding,
                        ComfortKeysLocalConflictRow.Kind.RESOLVABLE,
                        true);
            }

            return sortRows(byCommandContext);
        }

        private static List<ComfortKeysLocalConflictRow> scanUnresolvableSameKey(ScanContext ctx)
        {
            Map<String, ComfortKeysLocalConflictRow> byCommandContext = new LinkedHashMap<>();
            for (int i = 0; i < ctx.bindings.length; i++)
            {
                if (ctx.monitor != null && ctx.monitor.isCanceled())
                    break;
                if (ctx.monitor != null && (i % 50 == 0))
                    ctx.monitor.worked(1);

                Binding binding = ctx.bindings[i];
                if (!matchesCatalogBinding(ctx, binding))
                    continue;

                if (isCompetitorAssignmentUserChangeable(
                        ctx.keyController, binding, ctx.commandService))
                    continue;

                addRow(
                        byCommandContext,
                        ctx,
                        binding,
                        ComfortKeysLocalConflictRow.Kind.UNRESOLVABLE,
                        false);
            }

            return sortRows(byCommandContext);
        }

        private static void addRow(
                Map<String, ComfortKeysLocalConflictRow> byCommandContext,
                ScanContext ctx,
                Binding binding,
                ComfortKeysLocalConflictRow.Kind kind,
                boolean preferUserOnDuplicate)
        {
            ParameterizedCommand pc = binding.getParameterizedCommand();
            String commandId = pc != null ? pc.getId() : null;
            if (commandId == null || commandId.isBlank())
                return;

            String contextId = binding.getContextId();
            int bindingType = binding.getType();
            if (isSameCommandAsSelected(ctx.selected, commandId))
                return;

            String signature = commandId + '|' + contextId;
            String commandName = resolveCommandName(ctx.commandService, commandId);
            String contextName = resolveContextName(ctx.keyController, contextId);
            BindingElement bindingElement =
                    resolveBindingElementForCatalogBinding(ctx.keyController, binding);
            String sequenceFormatted = formatEffectiveSequence(bindingElement, binding);

            ComfortKeysLocalConflictRow candidate = new ComfortKeysLocalConflictRow(
                    kind,
                    commandId,
                    commandName,
                    contextId,
                    contextName,
                    sequenceFormatted,
                    bindingType,
                    bindingElement);

            ComfortKeysLocalConflictRow existing = byCommandContext.get(signature);
            if (existing == null)
            {
                byCommandContext.put(signature, candidate);
                return;
            }
            if (preferUserOnDuplicate
                    && existing.bindingType != Binding.USER
                    && candidate.bindingType == Binding.USER)
                byCommandContext.put(signature, candidate);
        }

        private static List<ComfortKeysLocalConflictRow> sortRows(
                Map<String, ComfortKeysLocalConflictRow> byCommandContext)
        {
            List<ComfortKeysLocalConflictRow> result = new ArrayList<>(byCommandContext.values());
            ComfortKeysLocalConflictRow.sortByCommandThenContext(result);
            return result;
        }

        private static boolean matchesCatalogBinding(ScanContext ctx, Binding binding)
        {
            if (binding == null)
                return false;

            if (!schemeMatches(binding.getSchemeId(), ctx.activeScheme))
                return false;
            if (!matchesPlatformLocale(binding, ctx.platform, ctx.locale))
                return false;

            KeySequence effectiveKey = resolveEffectiveKeyForMatch(ctx.keyController, binding);
            if (effectiveKey == null)
                return false;

            return matchesSequenceKey(effectiveKey, ctx.targetSequence);
        }

        /** Эффективная клавиша из строки Keys (U вместо устаревшей S в каталоге). */
        private static KeySequence resolveEffectiveKeyForMatch(
                KeyController keyController,
                Binding binding)
        {
            ParameterizedCommand pc = binding.getParameterizedCommand();
            String commandId = pc != null ? pc.getId() : null;
            if (commandId == null || commandId.isBlank() || binding == null)
                return null;

            String contextId = binding.getContextId();
            int bindingType = binding.getType();

            BindingElement keysRow = findBindingElementInModel(
                    keyController, commandId, contextId, bindingType);
            if (keysRow == null && bindingType == Binding.SYSTEM)
                keysRow = findBindingElementInModel(
                        keyController, commandId, contextId, Binding.USER);

            if (keysRow != null)
            {
                TriggerSequence effective = keysRow.getTrigger();
                if (effective instanceof KeySequence keySequence && !effective.isEmpty())
                    return keySequence;
            }

            TriggerSequence catalogTrigger = binding.getTriggerSequence();
            if (catalogTrigger instanceof KeySequence keySequence && !catalogTrigger.isEmpty())
                return keySequence;

            return null;
        }

        private static String formatEffectiveSequence(BindingElement bindingElement, Binding binding)
        {
            if (bindingElement != null)
            {
                TriggerSequence effective = bindingElement.getTrigger();
                if (effective != null && !effective.isEmpty())
                    return effective.format();
            }
            TriggerSequence catalogTrigger = binding.getTriggerSequence();
            if (catalogTrigger != null && !catalogTrigger.isEmpty())
                return catalogTrigger.format();
            return ""; //$NON-NLS-1$
        }

        /**
         * Может ли пользователь изменить назначение конкурента на странице Keys
         * (тип S/U сейчас не важен).
         */
        private static boolean isCompetitorAssignmentUserChangeable(
                KeyController keyController,
                Binding binding,
                ICommandService commandService)
        {
            if (binding == null)
                return false;

            ParameterizedCommand pc = binding.getParameterizedCommand();
            String commandId = pc != null ? pc.getId() : null;
            if (commandId == null || commandId.isBlank())
                return false;

            if (commandService == null)
                return false;

            Command command = commandService.getCommand(commandId);
            if (command == null)
                return false;

            try
            {
                if (!command.isDefined())
                    return false;
            }
            catch (Exception ignored)
            {
                return false;
            }

            return resolveBindingElementForCatalogBinding(keyController, binding) != null;
        }

        /** Та же команда — не конкурент (зеркала в других контекстах, S/U-двойник). */
        private static boolean isSameCommandAsSelected(
                BindingElement selected,
                String commandId)
        {
            if (commandId == null || commandId.isBlank())
                return false;
            return commandId.equals(resolveSelectedCommandId(selected));
        }

        private static String resolveSelectedCommandId(BindingElement selected)
        {
            String id = selected.getId();
            if (id != null && !id.isBlank())
                return id;

            try
            {
                Object modelObject = selected.getModelObject();
                if (modelObject instanceof Binding binding)
                {
                    ParameterizedCommand pc = binding.getParameterizedCommand();
                    if (pc != null)
                    {
                        String commandId = pc.getId();
                        if (commandId != null && !commandId.isBlank())
                            return commandId;
                    }
                }
            }
            catch (Exception ignored)
            {
                // fallback — getId()
            }

            try
            {
                Object pcObj = Global.invoke(selected, "getParameterizedCommand"); //$NON-NLS-1$
                if (pcObj instanceof ParameterizedCommand parameterizedCommand)
                {
                    String commandId = parameterizedCommand.getId();
                    if (commandId != null && !commandId.isBlank())
                        return commandId;
                }
            }
            catch (Exception ignored)
            {
                // package-private getParameterizedCommand
            }

            return id;
        }

        private static BindingElement resolveBindingElementForBinding(
                KeyController keyController,
                Binding binding)
        {
            if (keyController == null || binding == null)
                return null;

            try
            {
                Object element = keyController.getBindingModel()
                        .getBindingToElement()
                        .get(binding);
                if (element instanceof BindingElement bindingElement)
                    return bindingElement;
            }
            catch (Exception ignored)
            {
                // внутренний API изменился
            }

            return null;
        }

        private static BindingElement resolveBindingElementForCatalogBinding(
                KeyController keyController,
                Binding binding)
        {
            BindingElement element = resolveBindingElementForBinding(keyController, binding);
            if (element != null)
                return element;

            ParameterizedCommand pc = binding.getParameterizedCommand();
            String commandId = pc != null ? pc.getId() : null;
            if (commandId == null || commandId.isBlank())
                return null;

            return findBindingElementInModel(
                    keyController,
                    commandId,
                    binding.getContextId(),
                    binding.getType());
        }

        private static BindingElement findBindingElementInModel(
                KeyController keyController,
                String commandId,
                String contextId,
                int bindingType)
        {
            if (keyController == null || commandId == null || commandId.isBlank())
                return null;

            try
            {
                for (Object element : keyController.getBindingModel().getBindings())
                {
                    if (!(element instanceof BindingElement bindingElement))
                        continue;
                    if (!commandId.equals(bindingElement.getId()))
                        continue;
                    String modelContextId = resolveBindingElementContextId(bindingElement);
                    if (contextId == null
                            ? modelContextId != null
                            : !contextId.equals(modelContextId))
                        continue;
                    if (!matchesBindingElementType(bindingElement, bindingType))
                        continue;
                    return bindingElement;
                }
            }
            catch (Exception ignored)
            {
                // внутренний API изменился
            }

            return null;
        }

        private static boolean matchesBindingElementType(
                BindingElement bindingElement,
                int bindingType)
        {
            Integer userDelta = bindingElement.getUserDelta();
            if (userDelta != null)
                return userDelta.intValue() == bindingType;
            return bindingType == Binding.SYSTEM;
        }

        private static BindingService resolveBindingService(KeyController keyController)
        {
            try
            {
                Object service = Global.invoke(keyController, "getService"); //$NON-NLS-1$
                if (service instanceof BindingService bindingService)
                    return bindingService;
            }
            catch (Exception ignored)
            {
                // поле bindingService
            }

            try
            {
                Field field = KeyController.class.getDeclaredField("bindingService"); //$NON-NLS-1$
                field.setAccessible(true);
                Object service = field.get(keyController);
                if (service instanceof BindingService bindingService)
                    return bindingService;
            }
            catch (Exception ignored)
            {
                // fallback — workbench
            }

            IBindingService service =
                    PlatformUI.getWorkbench().getService(IBindingService.class);
            if (service instanceof BindingService bindingService)
                return bindingService;

            return null;
        }

        private static Binding[] resolveManagerBindings(KeyController keyController)
        {
            Object manager = resolveBindingManager(keyController);
            if (manager == null)
                return new Binding[0];

            try
            {
                Object bindings = Global.invoke(manager, "getBindings"); //$NON-NLS-1$
                if (bindings instanceof Binding[] arr)
                    return arr;
            }
            catch (Exception ignored)
            {
                // внутренний API изменился
            }

            return new Binding[0];
        }

        private static Object resolveBindingManager(KeyController keyController)
        {
            try
            {
                Object manager = Global.invoke(keyController, "getManager"); //$NON-NLS-1$
                if (manager != null)
                    return manager;
            }
            catch (Exception ignored)
            {
                // fBindingManager
            }

            try
            {
                Field field = KeyController.class.getDeclaredField("fBindingManager"); //$NON-NLS-1$
                field.setAccessible(true);
                return field.get(keyController);
            }
            catch (Exception ignored)
            {
                return null;
            }
        }

        private static String resolveActiveScheme(
                KeyController keyController,
                BindingService bindingService)
        {
            try
            {
                Object schemeModel = keyController.getSchemeModel();
                if (schemeModel != null)
                {
                    Object selected = Global.invoke(schemeModel, "getSelectedElement"); //$NON-NLS-1$
                    if (selected instanceof ModelElement element)
                    {
                        String id = element.getId();
                        if (id != null && !id.isBlank())
                            return id;
                    }
                }
            }
            catch (Exception ignored)
            {
                // activeSchemeId
            }

            try
            {
                Field field = KeyController.class.getDeclaredField("activeSchemeId"); //$NON-NLS-1$
                field.setAccessible(true);
                Object id = field.get(keyController);
                if (id instanceof String schemeId && !schemeId.isBlank())
                    return schemeId;
            }
            catch (Exception ignored)
            {
                // BindingService
            }

            if (bindingService != null && bindingService.getActiveScheme() != null)
                return bindingService.getActiveScheme().getId();

            return DEFAULT_SCHEME_ID;
        }

        private static String resolveBindingElementContextId(BindingElement selected)
        {
            ModelElement context = selected.getContext();
            if (context != null)
            {
                String id = context.getId();
                if (id != null && !id.isBlank())
                    return id;
            }

            try
            {
                Object id = Global.invoke(selected, "getContextId"); //$NON-NLS-1$
                if (id instanceof String contextId && !contextId.isBlank())
                    return contextId;
            }
            catch (Exception ignored)
            {
                // package-private getContextId
            }

            return null;
        }

        private static boolean matchesSequenceKey(KeySequence keySequence, KeySequence target)
        {
            if (target.equals(keySequence))
                return true;

            return target.getTriggers().length > 0
                    && keySequence.getTriggers().length > 0
                    && target.getTriggers()[0].equals(keySequence.getTriggers()[0]);
        }

        private static boolean schemeMatches(String schemeId, String activeScheme)
        {
            if (schemeId == null)
                return false;

            return activeScheme.equals(schemeId)
                    || DEFAULT_SCHEME_ID.equals(schemeId)
                    || DESIGNER_SCHEME_ID.equals(schemeId);
        }

        private static boolean matchesPlatformLocale(Binding binding, String platform, String locale)
        {
            String bindingPlatform = binding.getPlatform();
            if (bindingPlatform != null && !bindingPlatform.isBlank()
                    && platform != null && !bindingPlatform.equals(platform))
                return false;

            String bindingLocale = binding.getLocale();
            return bindingLocale == null || bindingLocale.isBlank()
                    || locale == null || bindingLocale.equals(locale);
        }

        private static String resolveCommandName(ICommandService commandService, String commandId)
        {
            if (commandService == null)
                return commandId;

            Command command = commandService.getCommand(commandId);
            if (command == null)
                return commandId;

            try
            {
                String name = command.getName();
                if (name != null && !name.isBlank())
                    return name;
            }
            catch (Exception ignored)
            {
                // команда ещё не определена
            }

            return commandId;
        }

        private static String resolveContextName(KeyController keyController, String contextId)
        {
            try
            {
                Object contextModel = keyController.getContextModel();
                if (contextModel == null)
                    return fallbackContextLabel(contextId);

                Object contexts = Global.invoke(contextModel, "getElements"); //$NON-NLS-1$
                if (!(contexts instanceof Iterable<?>))
                    contexts = Global.invoke(contextModel, "getContexts"); //$NON-NLS-1$

                if (!(contexts instanceof Iterable<?> iterable))
                    return fallbackContextLabel(contextId);

                for (Object ctx : iterable)
                {
                    Object id = Global.invoke(ctx, "getId"); //$NON-NLS-1$
                    if (!contextId.equals(id))
                        continue;

                    Object name = Global.invoke(ctx, "getName"); //$NON-NLS-1$
                    if (name instanceof String s && !s.isBlank())
                        return s;
                }
            }
            catch (Exception ignored)
            {
                // fallback — id контекста
            }

            return fallbackContextLabel(contextId);
        }

        private static String fallbackContextLabel(String contextId)
        {
            String known = knownContextLabel(contextId);
            return known != null ? known : contextId;
        }

        private static String knownContextLabel(String contextId)
        {
            if ("com._1c.g5.v8.dt.form.ui.ordinaryFormEditor".equals(contextId)) //$NON-NLS-1$
                return "Редактор формы"; //$NON-NLS-1$
            if ("com._1c.g5.v8.dt.form.ui.formEditor".equals(contextId)) //$NON-NLS-1$
                return "Редактор формы"; //$NON-NLS-1$
            if ("org.eclipse.xtext.ui.XtextEditorScope".equals(contextId)) //$NON-NLS-1$
                return "Редактирование текста"; //$NON-NLS-1$
            if ("org.eclipse.xtext.ui.embeddedTextEditorScope".equals(contextId)) //$NON-NLS-1$
                return "Вложенный текст"; //$NON-NLS-1$
            if ("tormozit.compareConf.context".equals(contextId)) //$NON-NLS-1$
                return "Редактор сравнения EDT"; //$NON-NLS-1$
            if ("tormozit.collection.context".equals(contextId)) //$NON-NLS-1$
                return "Окно коллекции"; //$NON-NLS-1$
            return null;
        }

        private static final class ScanContext
        {
            final KeyController keyController;
            final BindingElement selected;
            final KeySequence targetSequence;
            final String activeScheme;
            final String platform;
            final String locale;
            final ICommandService commandService;
            final Binding[] bindings;
            final IProgressMonitor monitor;

            ScanContext(
                    KeyController keyController,
                    BindingElement selected,
                    KeySequence targetSequence,
                    String activeScheme,
                    String platform,
                    String locale,
                    ICommandService commandService,
                    Binding[] bindings,
                    IProgressMonitor monitor)
            {
                this.keyController = keyController;
                this.selected = selected;
                this.targetSequence = targetSequence;
                this.activeScheme = activeScheme;
                this.platform = platform;
                this.locale = locale;
                this.commandService = commandService;
                this.bindings = bindings;
                this.monitor = monitor;
            }
        }
    }

}
