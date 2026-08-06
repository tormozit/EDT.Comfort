package tormozit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Штатный EDT «Фильтр по подсистемам» (в интерфейсе также «Отбор по подсистемам»):
 * автораскрытие ветки проекта из выделения навигатора и запоминание размеров окна
 * между сеансами.
 *
 * <p>Для диалога из сравнения конфигураций — запоминание переключателя источника
 * подсистем (MAIN/OTHER) на время сессии сравнения ({@code filterSettings} редактора).
 *
 * <p><b>TODO (наборы объектов):</b> наборы объектов в этом диалоге <em>не</em> показываются
 * и не выбираются — только штатное дерево подсистем. Связь с наборами сейчас только через
 * {@link ObjectSetSubsystemsFilterBridge} (фильтр «звезда» в навигаторе вытесняет фильтр
 * подсистем). Показ/выбор наборов внутри окна «Отбор по подсистемам» — возможная доработка
 * на будущее.
 */
public final class FilterBySubsystemsDialogHook implements IStartup
{
    private static final String HOOKED_KEY = "tormozit.filterBySubsystemsHooked"; //$NON-NLS-1$
    private static final String DIALOG_CLASS = "FilterBySubsystemsDialog"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Фильтр по подсистемам"; //$NON-NLS-1$

    private static final String SETTINGS_SECTION = "FilterBySubsystemsDialog"; //$NON-NLS-1$
    private static final String KEY_SHELL_X = "shell.x"; //$NON-NLS-1$
    private static final String KEY_SHELL_Y = "shell.y"; //$NON-NLS-1$
    private static final String KEY_SHELL_WIDTH = "shell.width"; //$NON-NLS-1$
    private static final String KEY_SHELL_HEIGHT = "shell.height"; //$NON-NLS-1$

    private static final int MIN_WIDTH = 400;
    private static final int MIN_HEIGHT = 300;
    private static final int MAX_PATCH_ATTEMPTS = 15;

    private static final String COMFORT_TOOLBAR_KEY = "tormozit.filterBySubsystemsComfortToolbar"; //$NON-NLS-1$
    private static final String MENU_SET_MARK = "Установить пометку с потомками"; //$NON-NLS-1$
    private static final String MENU_CLEAR_MARK = "Снять пометку с потомками"; //$NON-NLS-1$
    private static final String MENU_SAVE_PRESET = "Сохранить состояние фильтра…"; //$NON-NLS-1$
    private static final String MENU_LOAD_PRESET = "Загрузить состояние фильтра…"; //$NON-NLS-1$
    private static final String PRESET_DIALOG_TITLE = "Состояние фильтра по подсистемам"; //$NON-NLS-1$
    /**
     * Корень пути в пресете: имя проекта, либо {@code OTHER} для стороны коммита (git).
     */
    private static final String PRESET_OTHER_ROOT = "OTHER"; //$NON-NLS-1$

    private static final String SMART_FILTER_KEY = "tormozit.filterBySubsystemsSmartFilter"; //$NON-NLS-1$
    private static final String HIGHLIGHT_KEY = "tormozit.filterBySubsystemsHighlight"; //$NON-NLS-1$
    private static final String LAST_PATTERN_KEY = "tormozit.filterBySubsystemsLastPattern"; //$NON-NLS-1$
    private static final String DESELECT_ALWAYS_KEY = "tormozit.filterBySubsystemsDeselectAlways"; //$NON-NLS-1$
    private static final String LAST_CHILD_CHECK_KEY = "tormozit.filterBySubsystemsLastChildCheck"; //$NON-NLS-1$
    private static final String TREE_CONTEXT_MENU_KEY = "tormozit.filterBySubsystemsTreeContextMenu"; //$NON-NLS-1$
    private static final String CTRL_MARK_KEY = "tormozit.filterBySubsystemsCtrlMark"; //$NON-NLS-1$
    private static final String GRAY_MARK_HINT_KEY = "tormozit.filterBySubsystemsGrayMarkHint"; //$NON-NLS-1$
    private static final String GRAY_MARK_HINT = "CTRL+клик превратит соседние динамические пометки в статические"; //$NON-NLS-1$
    private static final String COPY_HOOKED_KEY = "tormozit.filterBySubsystemsCopyHooked"; //$NON-NLS-1$
    private static final String COPY_ACTIVE_COLUMN_KEY = "tormozit.filterBySubsystemsCopyColumn"; //$NON-NLS-1$

    /**
     * Режим «чёрный список» (скрывать объекты выбранных подсистем вместо показа только их) —
     * на жизнь {@code FilterBySubsystemsSettings}: у сравнения это
     * {@code ViewerFilterBySubsystemsSettings} ({@code namedFilter.filterBySubsystemSettings}),
     * у навигатора — {@code Navigator.getFilterBySubsystemsSettings()} / поле
     * {@code NavigatorSubsystemsFilter.filterBySubsystemsSettings}.
     * WeakHashMap на жизнь объекта settings; для навигатора дополнительно PreferenceStore
     * (EDT запоминает состав через memento, а settings при reload — новый объект).
     */
    private static final Map<Object, Boolean> BLACKLIST_BY_SUBSYSTEMS_SETTINGS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Персист чёрного списка фильтра подсистем навигатора. */
    private static final String PREF_NAVIGATOR_BLACKLIST =
        "comfort.navigator.filterBySubsystems.blacklist"; //$NON-NLS-1$

    /** Штатный id кнопки «Сбросить» ({@code FilterBySubsystemsDialog.TURN_OFF_ID}). */
    private static final int TURN_OFF_BUTTON_ID = 1024;

    private static final String BLACKLIST_CHECKBOX_KEY = "tormozit.filterBySubsystemsBlacklistCheckbox"; //$NON-NLS-1$
    private static final String BLACKLIST_CHECKBOX_LABEL =
        "Скрывать объекты выбранных подсистем (чёрный список)"; //$NON-NLS-1$
    private static final String BLACKLIST_RESET_HOOK_KEY = "tormozit.filterBySubsystemsBlacklistReset"; //$NON-NLS-1$

    private static final String INCLUDE_LABELS_KEY = "tormozit.filterBySubsystemsIncludeLabels"; //$NON-NLS-1$
    private static final String INCLUDE_SUBORDINATE_LABEL = "Включать подчинённые подсистемы"; //$NON-NLS-1$
    private static final String INCLUDE_PARENT_LABEL = "Включать родительские подсистемы"; //$NON-NLS-1$
    private static final String OUR_CHECKBOX_KEY = "tormozit.filterBySubsystemsOwnCheckbox"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, FilterBySubsystemsDialogHook::handleShow);
        FilterBySubsystemsDialogDebug.log("install Show filter"); //$NON-NLS-1$
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell))
            return;
        if (shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(HOOKED_KEY)))
            return;
        if (!isFilterBySubsystemsShell(shell))
            return;
        registerShell(shell);
    }

    private static void registerShell(Shell shell)
    {
        shell.setData(HOOKED_KEY, Boolean.TRUE);
        applyStoredShellBounds(shell);
        shell.addDisposeListener(e -> saveShellBounds((Shell) e.widget));
        schedulePatchAttempt(shell.getDisplay(), shell, 0);
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            if (tryPatchShell(shell, attempt))
                return;
            if (attempt < MAX_PATCH_ATTEMPTS)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatchShell(Shell shell, int attempt)
    {
        Object dialog = resolveDialog(shell);
        if (dialog == null)
        {
            FilterBySubsystemsDialogDebug.step("patch", "attempt=" + attempt + " dialog=null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        }

        Object panel = Global.getField(dialog, "subsystemsPanel"); //$NON-NLS-1$
        CheckboxTreeViewer viewer = resolveViewer(panel);
        if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
        {
            FilterBySubsystemsDialogDebug.step("patch", "attempt=" + attempt + " viewer=null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        }

        installComfortToolbarActions(dialog, panel, viewer);
        installTreeContextMarkMenu(panel, viewer);
        installLastChildCheckGuard(panel, viewer);
        installTreeCellCopy(viewer);
        installDeselectAllAlwaysEnabled(dialog, panel, viewer);
        installCompareBothSidesMode(dialog, panel, viewer);
        installGrayMarkHint(panel);
        installBlacklistCheckbox(dialog, panel);
        installStandardCheckboxLabels(shell, panel);
        expandNavigatorProject(viewer, attempt);
        return installSmartFilter(panel, viewer);
    }

    private static final String COMPARE_BOTH_SIDES_KEY = "tormozit.filterBySubsystemsBothSides"; //$NON-NLS-1$

    /**
     * Диалог сравнения: вместо переключения MAIN/OTHER показать оба проекта сторон
     * корнями дерева (как в навигаторе) и скрыть переключатель с заголовком стороны.
     */
    private static void installCompareBothSidesMode(Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        if (dialog == null || panel == null || viewer == null)
            return;
        Object toolItemObj = Global.getField(dialog, "toolBarChangeSideElement"); //$NON-NLS-1$
        if (!(toolItemObj instanceof ToolItem changeSide) || changeSide.isDisposed())
            return; // не диалог сравнения
        if (Boolean.TRUE.equals(viewer.getTree().getData(COMPARE_BOTH_SIDES_KEY)))
            return;
        viewer.getTree().setData(COMPARE_BOTH_SIDES_KEY, Boolean.TRUE);

        hideCompareSideSwitcher(dialog, panel, changeSide);
        installBothSidesContentProvider(dialog, panel, viewer);
        installBothSidesRootLabels(dialog, viewer);
        installCompareOkSaveGuard(dialog, panel, viewer);
        viewer.refresh();
        expandAllRootProjects(viewer);
        // drop() помечает только allProjects; у git OTHER — другой IDtProject с тем же именем.
        syncFullProjectMarksToBothCompareRoots(dialog, panel, viewer);
        FilterBySubsystemsDialogDebug.log("compareBothSides: tree shows MAIN+OTHER roots"); //$NON-NLS-1$
    }

    /**
     * После {@code FilterBySubsystemsSettings.drop()} в {@code checkedProjects} попадают
     * только проекты из {@code allProjects} (часто один workspace). В дереве сравнения
     * две стороны — два {@link IDtProject}: переносим полную статическую пометку на оба.
     */
    private static void syncFullProjectMarksToBothCompareRoots(
            Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        Object settings = Global.getField(panel, "currentFilterSettings"); //$NON-NLS-1$
        if (settings == null)
            settings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        if (settings == null || viewer == null || viewer.getTree() == null
                || viewer.getTree().isDisposed())
            return;
        if (countCheckedSubsystemsInSettings(settings) > 0)
            return;
        int projectsChecked = countCheckedProjectsInSettings(settings);
        int allProjects = countAllProjectsInSettings(settings);
        if (projectsChecked <= 0 || allProjects < 0 || projectsChecked < allProjects)
            return;

        LinkedHashSet<IDtProject> roots = new LinkedHashSet<>();
        IDtProject mainDt = resolveCompareSideDtProject(dialog, ComparisonSide.MAIN);
        IDtProject otherDt = resolveCompareSideDtProject(dialog, ComparisonSide.OTHER);
        if (mainDt != null)
            roots.add(mainDt);
        if (otherDt != null)
            roots.add(otherDt);
        for (TreeItem item : viewer.getTree().getItems())
        {
            if (item == null || item.isDisposed())
                continue;
            IDtProject dt = toDtProjectElement(item.getData());
            if (dt != null)
                roots.add(dt);
        }
        for (IDtProject dt : roots)
        {
            ensureProjectInAllProjects(settings, dt);
            setProjectMarkInSet(settings, "checkedProjects", dt, true); //$NON-NLS-1$
            setProjectMarkInSet(settings, "includeNotIncludedInSubsystems", dt, true); //$NON-NLS-1$
        }
        Object dialogSettings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        if (dialogSettings != null && dialogSettings != settings)
        {
            for (IDtProject dt : roots)
            {
                ensureProjectInAllProjects(dialogSettings, dt);
                setProjectMarkInSet(dialogSettings, "checkedProjects", dt, true); //$NON-NLS-1$
                setProjectMarkInSet(dialogSettings, "includeNotIncludedInSubsystems", dt, true); //$NON-NLS-1$
            }
        }
        clearViewerMarkCaches(panel, viewer);
        // Без grayNodes: полный обход getChildren на большой конфигурации — секунды.
        for (TreeItem item : viewer.getTree().getItems())
        {
            if (item == null || item.isDisposed())
                continue;
            Object data = item.getData();
            IDtProject dt = toDtProjectElement(data);
            if (dt == null || !roots.contains(dt))
                continue;
            item.setChecked(true);
            item.setGrayed(false);
            if (data != null)
            {
                viewer.setChecked(data, true);
                viewer.setGrayed(data, false);
            }
        }
    }

    /**
     * Подписи корней = имена сторон сравнения ({@code mainComparisonSideName}/
     * {@code otherComparisonSideName}), как заголовки колонок в редакторе.
     */
    private static void installBothSidesRootLabels(Object dialog, CheckboxTreeViewer viewer)
    {
        IBaseLabelProvider labelProvider = viewer.getLabelProvider();
        if (!(labelProvider instanceof DelegatingStyledCellLabelProvider delegating))
            return;
        IStyledLabelProvider inner = delegating.getStyledStringProvider();
        if (inner == null || inner instanceof CompareSideRootStyledProvider)
            return;
        injectStyledStringProvider(delegating, new CompareSideRootStyledProvider(dialog, inner));
    }

    /**
     * Перед штатным {@code okPressed → saveFilterSettings}: сброс userModified и синхрон
     * пометок из дерева — иначе {@code Optional.get} в {@code getComparisonSession}.
     */
    private static void installCompareOkSaveGuard(Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        Object btnObj = Global.invoke(dialog, "getButton", Integer.valueOf(IDialogConstants.OK_ID)); //$NON-NLS-1$
        if (!(btnObj instanceof Button ok) || ok.isDisposed())
            return;
        if (Boolean.TRUE.equals(ok.getData(COMPARE_BOTH_SIDES_KEY)))
            return;
        ok.setData(COMPARE_BOTH_SIDES_KEY, Boolean.TRUE);
        Listener[] existing = ok.getListeners(SWT.Selection);
        for (Listener listener : existing)
            ok.removeListener(SWT.Selection, listener);
        ok.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                try
                {
                    clearViewerMarkCaches(panel, viewer);
                    syncFilterSettingsFromViewer(dialog, panel, viewer);
                }
                catch (RuntimeException ex)
                {
                }
            }
        });
        for (Listener listener : existing)
            ok.addListener(SWT.Selection, listener);
    }

    private static void hideCompareSideSwitcher(Object dialog, Object panel, ToolItem changeSide)
    {
        if (!changeSide.isDisposed())
        {
            // Скрыть DROP_DOWN переключения стороны (иконка «свести»).
            changeSide.dispose();
            Global.setField(dialog, "toolBarChangeSideElement", null); //$NON-NLS-1$
        }
        Object labelObj = Global.getField(panel, "label"); //$NON-NLS-1$
        if (labelObj instanceof Control label && !label.isDisposed())
        {
            label.setVisible(false);
            Object layoutData = label.getLayoutData();
            if (layoutData instanceof GridData grid)
            {
                grid.exclude = true;
                grid.heightHint = 0;
            }
        }
        Global.invokeVoid(panel, "setLabelText", ""); //$NON-NLS-1$ //$NON-NLS-2$
        Object toolbarObj = Global.getField(panel, "toolBar"); //$NON-NLS-1$
        if (toolbarObj instanceof ToolBar toolbar && !toolbar.isDisposed())
            toolbar.getParent().layout(true, true);
        if (panel instanceof Composite composite && !composite.isDisposed())
            composite.layout(true, true);
    }

    private static void installBothSidesContentProvider(Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        Object provider = viewer.getContentProvider();
        if (!(provider instanceof ITreeContentProvider nativeProvider))
            return;
        if (nativeProvider instanceof BothSidesTreeContentProvider)
        {
            if (panel != null)
                Global.setField(panel, "treeContentProvider", nativeProvider); //$NON-NLS-1$
            installBothSidesCheckStateProvider(dialog, panel, viewer);
            return;
        }
        BothSidesTreeContentProvider bothSides = new BothSidesTreeContentProvider(dialog, nativeProvider);
        viewer.setContentProvider(bothSides);
        if (panel != null)
            Global.setField(panel, "treeContentProvider", bothSides); //$NON-NLS-1$
        installBothSidesCheckStateProvider(dialog, panel, viewer);
    }

    /**
     * Штатный isChecked → manager.getDtProject(subsystem) путает MAIN/OTHER при одном имени.
     * Читаем Long-id из settings по корню дерева.
     */
    private static void installBothSidesCheckStateProvider(
            Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        if (viewer == null || Boolean.TRUE.equals(viewer.getData(COMPARE_BOTH_SIDES_KEY + ".check"))) //$NON-NLS-1$
            return;
        org.eclipse.jface.viewers.ICheckStateProvider nativeProvider = resolveCheckStateProvider(viewer);
        if (nativeProvider == null)
            return;
        if (nativeProvider instanceof BothSidesCheckStateProvider)
        {
            viewer.setData(COMPARE_BOTH_SIDES_KEY + ".check", Boolean.TRUE); //$NON-NLS-1$
            return;
        }
        BothSidesCheckStateProvider wrapped =
                new BothSidesCheckStateProvider(dialog, panel, viewer, nativeProvider);
        viewer.setCheckStateProvider(wrapped);
        // ImprovedCheckboxTreeViewer дублирует provider в customized*.
        Object customized = Global.getField(viewer, "customizedCheckStateProvider"); //$NON-NLS-1$
        if (customized != null)
            Global.setField(customized, "delegate", wrapped); //$NON-NLS-1$
        viewer.setData(COMPARE_BOTH_SIDES_KEY + ".check", Boolean.TRUE); //$NON-NLS-1$
    }

    private static void expandAllRootProjects(CheckboxTreeViewer viewer)
    {
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider contentProvider))
            return;
        Object input = viewer.getInput();
        Object[] roots = contentProvider.getElements(input);
        if (roots == null)
            return;
        for (Object root : roots)
        {
            if (root != null)
                viewer.setExpandedState(root, true);
        }
    }

    /**
     * При «включать объекты подчинённых/родительских» штатный {@code setState} если у родителя
     * полная пометка, при клике по непомеченному потомку делает {@code setSubtreeChecked(parent, false)}
     * — сносит пометки всех детей. Если кликнули по последнему непомеченному ребёнку, это не нужно:
     * просто дописываем пометку и поднимаем родителя до полной.
     */
    private static void installLastChildCheckGuard(Object panel, CheckboxTreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(LAST_CHILD_CHECK_KEY)))
            return;

        Object listObj = Global.getField(viewer, "checkStateListeners"); //$NON-NLS-1$
        if (listObj == null)
            return;
        Object raw = Global.invoke(listObj, "getListeners"); //$NON-NLS-1$
        if (!(raw instanceof Object[] existing) || existing.length == 0)
            return;

        ICheckStateListener[] originals = new ICheckStateListener[existing.length];
        for (int i = 0; i < existing.length; i++)
        {
            if (!(existing[i] instanceof ICheckStateListener listener))
                return;
            if (listener instanceof LastChildCheckGuard)
            {
                tree.setData(LAST_CHILD_CHECK_KEY, Boolean.TRUE);
                return;
            }
            originals[i] = listener;
        }

        // CTRL-состояние последнего клика по дереву — CheckStateChangedEvent модификаторов
        // не несёт (JFace читает только TreeItem.getChecked()), а клик по серой пометке
        // для SWT выглядит как toggle «галка → пусто». Запоминаем на MouseDown/MouseUp.
        Listener ctrlTrack = ctrlTrackListener(tree);
        tree.addListener(SWT.MouseDown, ctrlTrack);
        tree.addListener(SWT.MouseUp, ctrlTrack);

        Global.invoke(listObj, "clear"); //$NON-NLS-1$
        Global.invoke(listObj, "add", new LastChildCheckGuard(panel, viewer, originals)); //$NON-NLS-1$
        tree.setData(LAST_CHILD_CHECK_KEY, Boolean.TRUE);
        FilterBySubsystemsDialogDebug.log("lastChildCheck: guard установлен"); //$NON-NLS-1$
    }

    private static Listener ctrlTrackListener(Tree tree)
    {
        return event ->
        {
            if (event.button == 1 && !tree.isDisposed())
                tree.setData(CTRL_MARK_KEY, (event.stateMask & SWT.CTRL) != 0);
        };
    }

    /** CTRL был зажат на последнем клике по дереву (см. {@link #ctrlTrackListener}). */
    private static boolean isCtrlClick(CheckboxTreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        return tree != null && !tree.isDisposed()
            && Boolean.TRUE.equals(tree.getData(CTRL_MARK_KEY));
    }

    private static void clearCtrlClick(CheckboxTreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree != null && !tree.isDisposed())
            tree.setData(CTRL_MARK_KEY, null);
    }

    /** Надпись-подсказка про CTRL+клик — внизу окна, сразу под панелью дерева. */
    private static void installGrayMarkHint(Object panel)
    {
        if (!(panel instanceof Composite panelComposite) || panelComposite.isDisposed())
            return;
        if (Boolean.TRUE.equals(panelComposite.getData(GRAY_MARK_HINT_KEY)))
            return;
        panelComposite.setData(GRAY_MARK_HINT_KEY, Boolean.TRUE);

        Composite host = panelComposite.getParent();
        if (host == null || host.isDisposed() || !(host.getLayout() instanceof GridLayout))
            host = panelComposite;

        Label hint = new Label(host, SWT.WRAP);
        hint.setText(GRAY_MARK_HINT);
        hint.setFont(host.getFont());
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hintData.verticalIndent = 3;
        hint.setLayoutData(hintData);
        if (host != panelComposite)
            hint.moveBelow(panelComposite);
        host.layout(true, true);
        FilterBySubsystemsDialogDebug.log("grayMarkHint: надпись добавлена"); //$NON-NLS-1$
    }

    /** Режим «чёрный список» включён для данных настроек фильтра (сравнение или навигатор). */
    static boolean isBlacklistMode(Object subsystemsSettings)
    {
        if (subsystemsSettings == null)
            return false;
        Boolean cached = BLACKLIST_BY_SUBSYSTEMS_SETTINGS.get(subsystemsSettings);
        if (cached != null)
            return cached.booleanValue();
        if (!isNavigatorSubsystemsSettings(subsystemsSettings))
            return false;
        boolean persisted = readNavigatorBlacklistPref();
        BLACKLIST_BY_SUBSYSTEMS_SETTINGS.put(subsystemsSettings, Boolean.valueOf(persisted));
        return persisted;
    }

    /** Сбросить режим «чёрный список» (кнопка «Сбросить» / сброс фильтра в тулбаре). */
    static void clearBlacklistMode(Object subsystemsSettings)
    {
        if (subsystemsSettings != null)
            setBlacklistMode(subsystemsSettings, false);
        syncAllOpenDialogBlacklistUi(false);
    }

    /** Снять/выставить флажок «чёрный список» во всех открытых окнах «Фильтр по подсистемам». */
    private static void syncAllOpenDialogBlacklistUi(boolean enabled)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        for (Shell shell : display.getShells())
        {
            if (shell == null || shell.isDisposed())
                continue;
            if (!Boolean.TRUE.equals(shell.getData(HOOKED_KEY)))
                continue;
            Object dialog = resolveDialog(shell);
            if (dialog == null)
                continue;
            setBlacklistModeOnDialog(dialog, enabled);
            Object panel = Global.getField(dialog, "subsystemsPanel"); //$NON-NLS-1$
            if (panel instanceof Composite panelComposite && !panelComposite.isDisposed())
            {
                Button box = findBlacklistCheckbox(panelComposite);
                if (box != null && !box.isDisposed())
                    box.setSelection(enabled);
            }
        }
    }

    private static void setBlacklistMode(Object subsystemsSettings, boolean enabled)
    {
        if (subsystemsSettings == null)
            return;
        BLACKLIST_BY_SUBSYSTEMS_SETTINGS.put(subsystemsSettings, Boolean.valueOf(enabled));
        if (isNavigatorSubsystemsSettings(subsystemsSettings))
            writeNavigatorBlacklistPref(enabled);
    }

    /**
     * В сравнении {@code filterSettings} и {@code currentFilterSettings} — разные объекты;
     * чекбокс должен помечать оба, иначе пресет/фильтр читают «пустой» ключ WeakHashMap.
     */
    private static void setBlacklistModeOnDialog(Object dialog, boolean enabled)
    {
        if (dialog == null)
            return;
        Object filterSettings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        Object panel = Global.getField(dialog, "subsystemsPanel"); //$NON-NLS-1$
        Object current = panel != null
                ? Global.getField(panel, "currentFilterSettings") : null; //$NON-NLS-1$
        if (filterSettings != null)
            setBlacklistMode(filterSettings, enabled);
        if (current != null && current != filterSettings)
            setBlacklistMode(current, enabled);
    }

    /** Источник истины для пресета: UI-чекбокс, иначе оба объекта settings. */
    private static boolean resolveBlacklistFromDialog(Object dialog, Object panel, Object settings)
    {
        if (panel instanceof Composite panelComposite && !panelComposite.isDisposed())
        {
            Button box = findBlacklistCheckbox(panelComposite);
            if (box != null && !box.isDisposed())
                return box.getSelection();
        }
        Object filterSettings = dialog != null
                ? Global.getField(dialog, "filterSettings") : null; //$NON-NLS-1$
        if (isBlacklistMode(filterSettings))
            return true;
        Object current = panel != null
                ? Global.getField(panel, "currentFilterSettings") : null; //$NON-NLS-1$
        if (isBlacklistMode(current))
            return true;
        return isBlacklistMode(settings);
    }

    /** Настройки диалога/фильтра навигатора, не сравнения ({@code ViewerFilterBySubsystemsSettings}). */
    private static boolean isNavigatorSubsystemsSettings(Object settings)
    {
        if (settings == null)
            return false;
        String name = settings.getClass().getName();
        return name.contains("FilterBySubsystemsSettings") //$NON-NLS-1$
                && !name.contains("ViewerFilterBySubsystemsSettings"); //$NON-NLS-1$
    }

    private static boolean readNavigatorBlacklistPref()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
            return false;
        return activator.getPreferenceStore().getBoolean(PREF_NAVIGATOR_BLACKLIST);
    }

    private static void writeNavigatorBlacklistPref(boolean enabled)
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
            return;
        activator.getPreferenceStore().setValue(PREF_NAVIGATOR_BLACKLIST, enabled);
    }

    /**
     * Чекбокс «чёрный список» — для диалогов сравнения и навигатора (у обоих есть
     * {@code filterSettings}: {@code ViewerFilterBySubsystemsSettings} /
     * {@code FilterBySubsystemsSettings}). Ключ — тот же объект settings, что читают
     * {@link CompareConfigMenuHook} и {@link ObjectSetSubsystemsFilterBridge}.
     */
    private static void installBlacklistCheckbox(Object dialog, Object panel)
    {
        if (!(panel instanceof Composite panelComposite) || panelComposite.isDisposed())
            return;
        Object settings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        if (settings == null)
            return;

        if (Boolean.TRUE.equals(panelComposite.getData(BLACKLIST_CHECKBOX_KEY)))
        {
            Button existing = findBlacklistCheckbox(panelComposite);
            if (existing != null)
                installBlacklistClearOnTurnOff(dialog, settings, existing);
            return;
        }
        panelComposite.setData(BLACKLIST_CHECKBOX_KEY, Boolean.TRUE);

        Composite host = panelComposite.getParent();
        if (host == null || host.isDisposed() || !(host.getLayout() instanceof GridLayout))
            host = panelComposite;

        Button checkbox = new Button(host, SWT.CHECK);
        checkbox.setText(BLACKLIST_CHECKBOX_LABEL);
        checkbox.setToolTipText(
            "Инвертирует отбор: объекты выбранных подсистем скрываются в дереве сравнения/навигатора, остальные показываются" //$NON-NLS-1$
                + Global.pluginSignForTooltip());
        checkbox.setSelection(isBlacklistMode(settings));
        checkbox.setData(OUR_CHECKBOX_KEY, Boolean.TRUE);
        GridData checkboxData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        checkboxData.verticalIndent = 3;
        checkbox.setLayoutData(checkboxData);
        checkbox.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                setBlacklistModeOnDialog(dialog, checkbox.getSelection());
                FilterBySubsystemsDialogDebug.step("blacklist", "checked=" + checkbox.getSelection()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
        installBlacklistClearOnTurnOff(dialog, settings, checkbox);
        host.layout(true, true);
        FilterBySubsystemsDialogDebug.log("blacklist: чекбокс добавлен"); //$NON-NLS-1$
    }

    private static Button findBlacklistCheckbox(Composite panelComposite)
    {
        Composite host = panelComposite.getParent();
        if (host == null || host.isDisposed())
            host = panelComposite;
        for (Control child : host.getChildren())
        {
            if (child instanceof Button button && !button.isDisposed()
                    && Boolean.TRUE.equals(button.getData(OUR_CHECKBOX_KEY)))
                return button;
        }
        return null;
    }

    /**
     * Кнопка «Сбросить» ({@code TURN_OFF_ID}) вызывает {@code filterSettings.drop()} и закрывает
     * диалог — дополнительно снимаем режим чёрного списка и галку чекбокса.
     */
    private static void installBlacklistClearOnTurnOff(Object dialog, Object settings, Button checkbox)
    {
        if (dialog == null || settings == null || checkbox == null || checkbox.isDisposed())
            return;
        Button turnOff = findTurnOffButton(dialog);
        if (turnOff == null)
            return;
        if (Boolean.TRUE.equals(turnOff.getData(BLACKLIST_RESET_HOOK_KEY)))
            return;
        turnOff.setData(BLACKLIST_RESET_HOOK_KEY, Boolean.TRUE);
        turnOff.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                clearBlacklistMode(settings);
                FilterBySubsystemsDialogDebug.step("blacklist", "cleared by Reset"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
        FilterBySubsystemsDialogDebug.log("blacklist: Reset clears checkbox"); //$NON-NLS-1$
    }

    private static Button findTurnOffButton(Object dialog)
    {
        Object btnObj = Global.invoke(dialog, "getButton", Integer.valueOf(TURN_OFF_BUTTON_ID)); //$NON-NLS-1$
        if (btnObj instanceof Button button && !button.isDisposed())
            return button;
        Object shellObj = Global.invoke(dialog, "getShell"); //$NON-NLS-1$
        if (!(shellObj instanceof Shell shell) || shell.isDisposed())
            return null;
        return findButtonRecursive(shell, "Сбросить"); //$NON-NLS-1$
    }

    private static Button findButtonRecursive(Composite root, String text)
    {
        for (Control child : root.getChildren())
        {
            if (child.isDisposed())
                continue;
            if (child instanceof Button button && (button.getStyle() & SWT.PUSH) != 0)
            {
                String label = button.getText();
                if (label != null && label.contains(text))
                    return button;
            }
            if (child instanceof Composite composite)
            {
                Button nested = findButtonRecursive(composite, text);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    /**
     * Ctrl+C при фокусе на дереве копирует текст активной ячейки. Тот же архитектурный потолок,
     * что и в {@code PreferenceSearchFilterAugmenter.wireTreeCopy}/{@code KeyBindingToastHook}:
     * буква C при зажатом Ctrl не порождает {@code SWT.KeyDown} в модальном диалоге — нативная
     * Win32-трансляция акселератора съедает её раньше. Перехват — через общий
     * {@link CopyCommandSupport}.
     */
    private static void installTreeCellCopy(CheckboxTreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(COPY_HOOKED_KEY)))
            return;
        tree.setData(COPY_HOOKED_KEY, Boolean.TRUE);
        // Активная колонка запоминается кликом по дереву (см. таблицы/деревья в кастомных окнах).
        tree.addListener(SWT.MouseDown, e ->
        {
            if (e.button != 1 || tree.isDisposed())
                return;
            TreeItem item = tree.getItem(new Point(e.x, e.y));
            if (item == null)
                return;
            tree.setData(COPY_ACTIVE_COLUMN_KEY, columnAt(tree, e.x, e.y, item));
        });
        CopyCommandSupport.wireCopyOverride(tree, () -> copyActiveCellText(tree));
        FilterBySubsystemsDialogDebug.log("cellCopy: hook установлен"); //$NON-NLS-1$
    }

    private static int columnAt(Tree tree, int x, int y, TreeItem item)
    {
        for (int i = 0; i < tree.getColumnCount(); i++)
        {
            Rectangle bounds = item.getBounds(i);
            if (bounds != null && !bounds.isEmpty() && bounds.contains(x, y))
                return i;
        }
        return 0;
    }

    private static void copyActiveCellText(Tree tree)
    {
        if (tree.isDisposed())
            return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;
        int column = 0;
        Object stored = tree.getData(COPY_ACTIVE_COLUMN_KEY);
        if (stored instanceof Integer col && col >= 0 && col < tree.getColumnCount())
            column = col;
        String text = selection[0].getText(column);
        if (text == null || text.isBlank())
            return;
        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
        FilterBySubsystemsDialogDebug.step("cellCopy", //$NON-NLS-1$
            "column=" + column + " len=" + text.length()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Штатный {@code AbstractSubsystemsPanel.changeActionEnable} при включённом
     * «включать объекты подчинённых/родительских» активирует «Снять отметку со всех»
     * только если помечены все корни. Держим кнопку всегда доступной.
     */
    private static void installDeselectAllAlwaysEnabled(
            Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        Object itemObj = Global.getField(panel, "toolBarDeselectAllElement"); //$NON-NLS-1$
        if (!(itemObj instanceof ToolItem deselect) || deselect.isDisposed())
            return;
        if (Boolean.TRUE.equals(deselect.getData(DESELECT_ALWAYS_KEY)))
            return;
        deselect.setData(DESELECT_ALWAYS_KEY, Boolean.TRUE);

        Runnable keepEnabled = () ->
        {
            if (!deselect.isDisposed())
                deselect.setEnabled(true);
        };
        keepEnabled.run();

        // Штатный ICheckStateListener сначала вызывает changeActionEnable — наш после него.
        viewer.addCheckStateListener((ICheckStateListener) event -> keepEnabled.run());

        Object selectAllObj = Global.getField(panel, "toolBarSelectAllElement"); //$NON-NLS-1$
        if (selectAllObj instanceof ToolItem selectAll && !selectAll.isDisposed())
            selectAll.addSelectionListener(afterSelection(keepEnabled));
        deselect.addSelectionListener(afterSelection(keepEnabled));

        if (dialog != null)
        {
            wireKeepEnabledAfterButton(dialog, "includeFromSubordinateCheckbox", keepEnabled); //$NON-NLS-1$
            wireKeepEnabledAfterButton(dialog, "includeFromParentCheckbox", keepEnabled); //$NON-NLS-1$
        }
        FilterBySubsystemsDialogDebug.log("deselectAll: всегда доступна"); //$NON-NLS-1$
    }

    private static void wireKeepEnabledAfterButton(Object dialog, String field, Runnable keepEnabled)
    {
        Object buttonObj = Global.getField(dialog, field);
        if (!(buttonObj instanceof Button button) || button.isDisposed())
            return;
        button.addSelectionListener(afterSelection(keepEnabled));
    }

    /**
     * Штатные подписи «Включать объекты подчинённых/родительских подсистем» короче и меньше
     * противоречат новому флажку «чёрный список» («Скрывать объекты выбранных подсистем»).
     *
     * <p>Полей {@code includeFromSubordinateCheckbox}/{@code includeFromParentCheckbox} в классе
     * диалога нет (проверено декомпиляцией {@code FilterBySubsystemsDialog}) — кнопки ищем по
     * дереву виджетов: они лежат в том же родителе, что и {@code subsystemsPanel}
     * (см. {@link #installGrayMarkHint}/{@link #installBlacklistCheckbox}), созданы раньше нашего
     * чекбокса чёрного списка (который помечен {@link #OUR_CHECKBOX_KEY}) и идут в порядке
     * «подчинённые», затем «родительские» ({@code createIncludeFromSubordinateButton} перед
     * {@code createIncludeFromParentButton} в {@code createDialogArea}).
     */
    private static void installStandardCheckboxLabels(Shell shell, Object panel)
    {
        if (shell == null || shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(INCLUDE_LABELS_KEY)))
            return;
        if (!(panel instanceof Composite panelComposite) || panelComposite.isDisposed())
            return;
        Composite host = panelComposite.getParent();
        if (host == null || host.isDisposed())
            return;
        shell.setData(INCLUDE_LABELS_KEY, Boolean.TRUE);

        List<Button> nativeChecks = new ArrayList<>();
        for (Control child : host.getChildren())
        {
            if (child instanceof Button button && !button.isDisposed()
                    && (button.getStyle() & SWT.CHECK) != 0
                    && !Boolean.TRUE.equals(button.getData(OUR_CHECKBOX_KEY)))
                nativeChecks.add(button);
        }
        if (nativeChecks.size() > 0)
            nativeChecks.get(0).setText(INCLUDE_SUBORDINATE_LABEL);
        if (nativeChecks.size() > 1)
            nativeChecks.get(1).setText(INCLUDE_PARENT_LABEL);
        host.layout(true, true);
        FilterBySubsystemsDialogDebug.step("labels", "renamed=" + nativeChecks.size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static SelectionAdapter afterSelection(Runnable action)
    {
        return new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                action.run();
            }
        };
    }

    private static void applyStoredShellBounds(Shell shell)
    {
        IDialogSettings settings = dialogSettings();
        if (settings.get(KEY_SHELL_WIDTH) == null || settings.get(KEY_SHELL_HEIGHT) == null)
            return;

        int width = settings.getInt(KEY_SHELL_WIDTH);
        int height = settings.getInt(KEY_SHELL_HEIGHT);
        if (width <= 0 || height <= 0)
            return;

        int x = settings.getInt(KEY_SHELL_X);
        int y = settings.getInt(KEY_SHELL_Y);
        Rectangle bounds = clampToMonitor(shell.getDisplay(),
            new Rectangle(x, y, width, height), MIN_WIDTH, MIN_HEIGHT);
        shell.setBounds(bounds);
        FilterBySubsystemsDialogDebug.log("restore bounds " + bounds); //$NON-NLS-1$
    }

    private static void saveShellBounds(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        if (shell.getMinimized())
            return;

        Rectangle bounds = shell.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
            return;

        Rectangle clamped = clampToMonitor(shell.getDisplay(), bounds, MIN_WIDTH, MIN_HEIGHT);
        IDialogSettings settings = dialogSettings();
        settings.put(KEY_SHELL_X, clamped.x);
        settings.put(KEY_SHELL_Y, clamped.y);
        settings.put(KEY_SHELL_WIDTH, clamped.width);
        settings.put(KEY_SHELL_HEIGHT, clamped.height);
        FilterBySubsystemsDialogDebug.log("save bounds " + clamped); //$NON-NLS-1$
    }

    private static void expandNavigatorProject(CheckboxTreeViewer viewer, int attempt)
    {
        IProject project = resolveNavigatorProject();
        if (project == null)
        {
            FilterBySubsystemsDialogDebug.step("expand", "attempt=" + attempt + " no navigator project"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }

        Object input = viewer.getInput();
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider contentProvider))
            return;

        Object[] roots = contentProvider.getElements(input);
        Object projectElement = findProjectElement(roots, project);
        if (projectElement == null)
        {
            FilterBySubsystemsDialogDebug.step("expand", "attempt=" + attempt //$NON-NLS-1$ //$NON-NLS-2$
                + " project=" + project.getName() + " not in tree"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        viewer.setExpandedState(projectElement, true);
        viewer.reveal(projectElement);
        FilterBySubsystemsDialogDebug.step("expand", "attempt=" + attempt //$NON-NLS-1$ //$NON-NLS-2$
            + " project=" + project.getName()); //$NON-NLS-1$
    }

    private static CheckboxTreeViewer resolveViewer(Object panel)
    {
        if (panel == null)
            return null;
        Object viewer = Global.getField(panel, "viewer"); //$NON-NLS-1$
        return viewer instanceof CheckboxTreeViewer checkboxTreeViewer ? checkboxTreeViewer : null;
    }

    private static void installComfortToolbarActions(Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        Object toolbarObj = Global.getField(panel, "toolBar"); //$NON-NLS-1$
        if (!(toolbarObj instanceof ToolBar toolbar) || toolbar.isDisposed())
            return;
        if (Boolean.TRUE.equals(toolbar.getData(COMFORT_TOOLBAR_KEY)))
            return;
        toolbar.setData(COMFORT_TOOLBAR_KEY, Boolean.TRUE);

        ToolItem comfortItem = new ToolItem(toolbar, SWT.DROP_DOWN);
        comfortItem.setText("Комфорт"); //$NON-NLS-1$
        comfortItem.setToolTipText(
            "Пометка подсистем и сохранение состояния фильтра" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        comfortItem.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                showComfortMarkMenu(comfortItem, toolbar, dialog, panel, viewer);
            }
        });
        if (panel instanceof Composite panelComposite && !panelComposite.isDisposed())
            panelComposite.layout(true, true);
        FilterBySubsystemsDialogDebug.log("toolbar: добавлена кнопка «Комфорт»"); //$NON-NLS-1$
    }

    private static boolean installSmartFilter(Object panel, CheckboxTreeViewer viewer)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return true;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        if (Boolean.TRUE.equals(tree.getData(SMART_FILTER_KEY)))
            return true;

        Object searchBoxObj = Global.getField(panel, "searchBox"); //$NON-NLS-1$
        if (!(searchBoxObj instanceof SearchBox searchBox) || searchBox.isDisposed())
        {
            FilterBySubsystemsDialogDebug.step("smartFilter", "searchBox=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        IBaseLabelProvider labelProvider = viewer.getLabelProvider();
        if (!(labelProvider instanceof DelegatingStyledCellLabelProvider delegating))
        {
            FilterBySubsystemsDialogDebug.step("smartFilter", "labelProvider=" //$NON-NLS-1$ //$NON-NLS-2$
                + (labelProvider != null ? labelProvider.getClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        IStyledLabelProvider inner = delegating.getStyledStringProvider();
        if (inner == null)
        {
            FilterBySubsystemsDialogDebug.step("smartFilter", "inner=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        SubsystemHighlightStyledProvider highlight = new SubsystemHighlightStyledProvider(inner);
        injectStyledStringProvider(delegating, highlight);
        SmartMatchHighlight.enableColorsOnSelection(delegating);

        SmartSubsystemsFilter filter = new SmartSubsystemsFilter(inner);
        tree.setData(SMART_FILTER_KEY, filter);
        tree.setData(HIGHLIGHT_KEY, highlight);
        tree.setData(LAST_PATTERN_KEY, ""); //$NON-NLS-1$

        searchBox.setMessage("Фильтр"); //$NON-NLS-1$
        searchBox.setToolTipText(
            FilterInputBox.SUBSYSTEMS_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
        FilterInputBox.attachHistory(searchBox, FilterInputBox.Scope.FILTER_BY_SUBSYSTEMS);
        searchBox.setMinimumSearchTextLength(0);
        searchBox.setJobScheduleDelay(0);
        searchBox.setRunSearchOnTextChange(true);
        searchBox.setSearchListener(new SearchBox.ISearchListener()
        {
            @Override
            public void performSearch(String text, IProgressMonitor monitor)
            {
                applySmartFilter(panel, viewer, text);
            }
        });

        String initial = searchBox.getText();
        if (initial != null && !initial.isEmpty())
            applySmartFilter(panel, viewer, initial);

        FilterBySubsystemsDialogDebug.log("smartFilter: установлен"); //$NON-NLS-1$
        return true;
    }

    private static void applySmartFilter(Object panel, CheckboxTreeViewer viewer, String text)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        String pattern = text != null ? text.trim() : ""; //$NON-NLS-1$
        String last = tree.getData(LAST_PATTERN_KEY) instanceof String lastStr ? lastStr : ""; //$NON-NLS-1$
        if (pattern.equals(last))
            return;

        Object filterObj = tree.getData(SMART_FILTER_KEY);
        if (!(filterObj instanceof SmartSubsystemsFilter filter))
            return;
        Object highlightObj = tree.getData(HIGHLIGHT_KEY);
        if (highlightObj instanceof SubsystemHighlightStyledProvider highlight)
            highlight.setHighlightPattern(pattern);
        filter.setPattern(pattern);

        // Штатный addViewerFilter/removeViewerFilter (AbstractViewerPanel) перед сменой
        // фильтра снимает снимок пометок (checkedElements/grayedElements) и после снятия
        // восстанавливает их — иначе refresh CheckboxTreeViewer теряет клики при фильтре.
        Object nativeFilterObj = Global.getField(panel, "searchFilterWithHistory"); //$NON-NLS-1$
        if (nativeFilterObj instanceof ViewerFilter nativeFilter
            && Arrays.asList(viewer.getFilters()).contains(nativeFilter))
            Global.invokeVoid(panel, "removeViewerFilter", nativeFilter); //$NON-NLS-1$

        boolean filtering = !pattern.isEmpty();
        List<ViewerFilter> current = Arrays.asList(viewer.getFilters());
        if (filtering)
        {
            // Уже установлен — тоже вызываем: внутри снова updateCheckedAndGrayedElements + refresh
            Global.invokeVoid(panel, "addViewerFilter", filter); //$NON-NLS-1$
            viewer.expandAll();
        }
        else if (current.contains(filter))
        {
            // removeViewerFilter делает collapseAll — без снимка теряется текущая строка
            ISelection selection = viewer.getSelection();
            Global.invokeVoid(panel, "removeViewerFilter", filter); //$NON-NLS-1$
            restoreTreeSelection(viewer, selection);
        }

        tree.setData(LAST_PATTERN_KEY, pattern);
        FilterBySubsystemsDialogDebug.step("smartFilter", "pattern=\"" + pattern //$NON-NLS-1$ //$NON-NLS-2$
            + "\" filtering=" + filtering); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void restoreTreeSelection(CheckboxTreeViewer viewer, ISelection selection)
    {
        if (selection == null || selection.isEmpty())
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        viewer.setSelection(selection, true);
    }

    private static void injectStyledStringProvider(DelegatingStyledCellLabelProvider provider,
            IStyledLabelProvider smartProvider)
    {
        Class<?> cls = provider.getClass();
        while (cls != null)
        {
            for (java.lang.reflect.Field field : cls.getDeclaredFields())
            {
                if (IStyledLabelProvider.class.isAssignableFrom(field.getType()))
                {
                    try
                    {
                        field.setAccessible(true);
                        field.set(provider, smartProvider);
                        return;
                    }
                    catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private static void installTreeContextMarkMenu(Object panel, CheckboxTreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(TREE_CONTEXT_MENU_KEY)))
            return;

        Menu menu = tree.getMenu();
        if (menu == null)
        {
            menu = new Menu(tree);
            tree.setMenu(menu);
        }
        final Menu contextMenu = menu;

        MenuItem setMark = fillSubtreeMarkMenuItem(contextMenu, panel, viewer, true,
            isIncludeObjectsFromSubordinateSubsystems(panel));
        MenuItem clearMark = fillSubtreeMarkMenuItem(contextMenu, panel, viewer, false,
            isIncludeObjectsFromSubordinateSubsystems(panel));

        MenuAdapter enableAdapter = new MenuAdapter()
        {
            @Override public void menuShown(MenuEvent e)
            {
                IStructuredSelection selection = viewer.getStructuredSelection();
                boolean hasSelection = selection != null && !selection.isEmpty();
                boolean fromSubordinate = isIncludeObjectsFromSubordinateSubsystems(panel);
                if (!setMark.isDisposed())
                {
                    setMark.setEnabled(hasSelection && !fromSubordinate);
                    setMark.setToolTipText(subtreeMarkTooltip(true, fromSubordinate));
                }
                if (!clearMark.isDisposed())
                {
                    clearMark.setEnabled(hasSelection && !fromSubordinate);
                    clearMark.setToolTipText(subtreeMarkTooltip(false, fromSubordinate));
                }
            }
        };
        contextMenu.addMenuListener(enableAdapter);
        tree.setData(TREE_CONTEXT_MENU_KEY, Boolean.TRUE);
        tree.addDisposeListener(ev ->
        {
            if (!contextMenu.isDisposed())
                contextMenu.removeMenuListener(enableAdapter);
        });
        FilterBySubsystemsDialogDebug.log("treeContextMenu: команды пометки добавлены"); //$NON-NLS-1$
    }

    private static void showComfortMarkMenu(
            ToolItem item, ToolBar toolbar, Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        if (viewer.getTree() == null || viewer.getTree().isDisposed())
            return;

        IStructuredSelection selection = viewer.getStructuredSelection();
        boolean hasSelection = selection != null && !selection.isEmpty();
        boolean fromSubordinate = isIncludeObjectsFromSubordinateSubsystems(panel);

        Menu menu = new Menu(toolbar.getShell(), SWT.POP_UP);
        MenuItem setMark = fillSubtreeMarkMenuItem(menu, panel, viewer, true, fromSubordinate);
        setMark.setEnabled(hasSelection && !fromSubordinate);
        MenuItem clearMark = fillSubtreeMarkMenuItem(menu, panel, viewer, false, fromSubordinate);
        clearMark.setEnabled(hasSelection && !fromSubordinate);

        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem saveItem = ComfortSubmenuHelper.createSortedMenuItem(menu, SWT.PUSH,
                MENU_SAVE_PRESET);
        saveItem.setToolTipText(
                "Сохранить текущие пометки, флаги и чёрный список под именем" //$NON-NLS-1$
                        + Global.pluginSignForTooltip());
        saveItem.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                // После закрытия POP_UP — иначе InputDialog на модальном сравнении не открывается.
                toolbar.getDisplay().asyncExec(() -> saveFilterPresetFromDialog(dialog, panel));
            }
        });
        MenuItem loadItem = ComfortSubmenuHelper.createSortedMenuItem(menu, SWT.PUSH,
                MENU_LOAD_PRESET);
        loadItem.setToolTipText(
                "Загрузить ранее сохранённое состояние фильтра по подсистемам" //$NON-NLS-1$
                        + Global.pluginSignForTooltip());
        loadItem.setEnabled(!FilterPresetStore.listNames().isEmpty());
        loadItem.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                toolbar.getDisplay().asyncExec(
                        () -> loadFilterPresetIntoDialog(dialog, panel, viewer));
            }
        });

        Rectangle bounds = item.getBounds();
        Point location = toolbar.toDisplay(bounds.x, bounds.y + bounds.height);
        menu.setLocation(location);
        menu.setVisible(true);
        menu.addMenuListener(new MenuAdapter()
        {
            @Override public void menuHidden(MenuEvent e)
            {
                toolbar.getDisplay().asyncExec(menu::dispose);
            }
        });
    }

    private static boolean isIncludeObjectsFromSubordinateSubsystems(Object panel)
    {
        Object settings = Global.getField(panel, "currentFilterSettings"); //$NON-NLS-1$
        if (settings == null)
            return false;
        return Boolean.TRUE.equals(Global.invoke(
            settings, "isIncludeObjectsFromSubordinateSubsystems")); //$NON-NLS-1$
    }

    private static MenuItem fillSubtreeMarkMenuItem(
            Menu menu, Object panel, CheckboxTreeViewer viewer, boolean setMark, boolean fromSubordinate)
    {
        String label = setMark ? MENU_SET_MARK : MENU_CLEAR_MARK;
        MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(menu, SWT.PUSH, label);
        item.setToolTipText(subtreeMarkTooltip(setMark, fromSubordinate));
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                applySubtreeMark(panel, viewer, setMark);
            }
        });
        return item;
    }

    private static String subtreeMarkTooltip(boolean setMark, boolean fromSubordinate)
    {
        String base = setMark
            ? "Отметить выделенную подсистему и все её подчинённые подсистемы" //$NON-NLS-1$
            : "Снять отметку с выделенной подсистемы и всех её подчинённых подсистем"; //$NON-NLS-1$
        if (fromSubordinate)
            base += ". Недоступно при включённом «Включать объекты из подчинённых подсистем»"; //$NON-NLS-1$
        return base + Global.pluginSignForTooltip();
    }

    private static void applySubtreeMark(Object panel, CheckboxTreeViewer viewer, boolean checked)
    {
        IStructuredSelection selection = viewer.getStructuredSelection();
        if (selection == null || selection.isEmpty())
            return;
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider provider))
            return;

        List<Object> nodes = new ArrayList<>();
        try
        {
            for (Object element : selection.toList())
                collectSubtree(provider, element, nodes);
        }
        catch (RuntimeException ex)
        {
            FilterBySubsystemsDialogDebug.step("mark", "collect failed: " + ex); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (nodes.isEmpty())
            return;

        for (Object node : nodes)
        {
            try
            {
                viewer.setGrayed(node, false);
                if (checked)
                    Global.invokeVoid(panel, "setNodeChecked", node); //$NON-NLS-1$
                else
                    Global.invokeVoid(panel, "setNodeUnchecked", node); //$NON-NLS-1$
                syncCheckedStateSets(panel, node, checked);
            }
            catch (RuntimeException ex)
            {
                FilterBySubsystemsDialogDebug.step("mark", "node failed: " + ex); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        Global.invokeVoid(panel, "changeActionEnable"); //$NON-NLS-1$
        forceDeselectAllEnabled(panel);
        FilterBySubsystemsDialogDebug.step("mark",
            (checked ? "set" : "clear") + " nodes=" + nodes.size()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void forceDeselectAllEnabled(Object panel)
    {
        Object itemObj = Global.getField(panel, "toolBarDeselectAllElement"); //$NON-NLS-1$
        if (itemObj instanceof ToolItem deselect && !deselect.isDisposed())
            deselect.setEnabled(true);
    }

    private static void saveFilterPresetFromDialog(Object dialog, Object panel)
    {
        Shell shell = dialogShell(dialog);
        if (shell == null || shell.isDisposed())
            return;
        try
        {
            CheckboxTreeViewer viewer = resolveViewer(panel);
            if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
            {
                MessageDialog.openError(shell, PRESET_DIALOG_TITLE, "Дерево фильтра недоступно."); //$NON-NLS-1$
                return;
            }
            Object settings = Global.getField(panel, "currentFilterSettings"); //$NON-NLS-1$
            if (settings == null)
                settings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
            List<String> paths = collectStaticMarkPaths(dialog, viewer);
            boolean blacklist = resolveBlacklistFromDialog(dialog, panel, settings);
            boolean includeSub = settings != null && Boolean.TRUE.equals(Global.invoke(settings,
                    "isIncludeObjectsFromSubordinateSubsystems")); //$NON-NLS-1$
            boolean includeParent = settings != null && Boolean.TRUE.equals(Global.invoke(settings,
                    "isIncludeObjectsFromParentSubsystems")); //$NON-NLS-1$

            InputDialog nameDialog = new InputDialog(shell, PRESET_DIALOG_TITLE,
                    "Имя сохранённого состояния:", "", null); //$NON-NLS-1$ //$NON-NLS-2$
            if (nameDialog.open() != Window.OK)
                return;
            String name = nameDialog.getValue() != null ? nameDialog.getValue().trim() : ""; //$NON-NLS-1$
            if (name.isEmpty())
            {
                MessageDialog.openWarning(shell, PRESET_DIALOG_TITLE, "Имя не может быть пустым."); //$NON-NLS-1$
                return;
            }
            if (FilterPresetStore.contains(name)
                    && !MessageDialog.openQuestion(shell, PRESET_DIALOG_TITLE,
                            "Состояние «" + name + "» уже есть. Перезаписать?")) //$NON-NLS-1$ //$NON-NLS-2$
                return;
            FilterPresetStore.put(name, new FilterPresetStore.PresetSnapshot(
                    paths, blacklist, includeSub, includeParent));
            setBlacklistModeOnDialog(dialog, blacklist);
        }
        catch (RuntimeException ex)
        {
            MessageDialog.openError(shell, PRESET_DIALOG_TITLE,
                    "Ошибка сохранения: " + ex.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Статические (не серые) пометки → полные пути в дереве.
     * Корень: {@link IDtProject#getName()} или {@link #PRESET_OTHER_ROOT} для стороны коммита.
     */
    private static List<String> collectStaticMarkPaths(Object dialog, CheckboxTreeViewer viewer)
    {
        List<String> paths = new ArrayList<>();
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return paths;
        collectStaticMarkPathsRec(dialog, viewer, tree.getItems(), paths);
        return paths;
    }

    private static void collectStaticMarkPathsRec(
            Object dialog, CheckboxTreeViewer viewer, TreeItem[] items, List<String> paths)
    {
        if (items == null)
            return;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            Object data = item.getData();
            if (data != null && viewer.getChecked(data) && !viewer.getGrayed(data))
            {
                // Корень проекта: серая/полная пометка от детей — динамическая, не в пресет.
                // В логе был лишний путь «OTHER» → при load ставилась полная галка на корне.
                if ((data instanceof IDtProject || data instanceof IProject)
                        && treeItemHasCheckedDescendant(viewer, item))
                {
                    collectStaticMarkPathsRec(dialog, viewer, item.getItems(), paths);
                    continue;
                }
                String path = buildMarkPath(dialog, item);
                if (path != null && !path.isEmpty())
                    paths.add(path);
            }
            collectStaticMarkPathsRec(dialog, viewer, item.getItems(), paths);
        }
    }

    private static boolean treeItemHasCheckedDescendant(CheckboxTreeViewer viewer, TreeItem item)
    {
        if (item == null || item.isDisposed() || viewer == null)
            return false;
        for (TreeItem child : item.getItems())
        {
            if (child == null || child.isDisposed())
                continue;
            Object data = child.getData();
            if (data != null && viewer.getChecked(data))
                return true;
            if (treeItemHasCheckedDescendant(viewer, child))
                return true;
        }
        return false;
    }

    private static String buildMarkPath(Object dialog, TreeItem item)
    {
        List<String> segments = new ArrayList<>();
        TreeItem cur = item;
        while (cur != null && !cur.isDisposed())
        {
            segments.add(0, cur.getText() != null ? cur.getText() : ""); //$NON-NLS-1$
            cur = cur.getParentItem();
        }
        if (segments.isEmpty())
            return null;
        TreeItem rootItem = item;
        while (rootItem.getParentItem() != null)
            rootItem = rootItem.getParentItem();
        Object rootData = rootItem.getData();
        if (rootData instanceof IDtProject dt)
            segments.set(0, rootKeyForPreset(dialog, dt));
        return String.join(".", segments); //$NON-NLS-1$
    }

    private static String rootKeyForPreset(Object dialog, IDtProject rootDt)
    {
        if (rootDt == null)
            return "?"; //$NON-NLS-1$
        IDtProject mainDt = resolveCompareSideDtProject(dialog, ComparisonSide.MAIN);
        IDtProject otherDt = resolveCompareSideDtProject(dialog, ComparisonSide.OTHER);
        if (otherDt != null && rootDt == otherDt && mainDt != null && mainDt != otherDt)
        {
            if (isGitCompareFilterDialog(dialog)
                    || (mainDt.getName() != null && mainDt.getName().equals(otherDt.getName())))
                return PRESET_OTHER_ROOT;
        }
        String name = rootDt.getName();
        return name != null && !name.isEmpty() ? name : PRESET_OTHER_ROOT;
    }

    private static void loadFilterPresetIntoDialog(
            Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        Shell shell = dialogShell(dialog);
        if (shell == null)
            return;
        List<String> names = FilterPresetStore.listNames();
        if (names.isEmpty())
        {
            MessageDialog.openInformation(shell, PRESET_DIALOG_TITLE,
                    "Нет сохранённых состояний фильтра."); //$NON-NLS-1$
            return;
        }
        LoadPresetDialog pick = new LoadPresetDialog(shell, names);
        if (pick.open() != Window.OK)
            return;
        String name = pick.getSelectedName();
        if (name == null || name.isEmpty())
            return;
        FilterPresetStore.PresetSnapshot snap;
        try
        {
            snap = FilterPresetStore.load(name);
        }
        catch (RuntimeException ex)
        {
            MessageDialog.openError(shell, PRESET_DIALOG_TITLE,
                    "Ошибка загрузки «" + name + "»: " + ex.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (snap == null)
        {
            MessageDialog.openError(shell, PRESET_DIALOG_TITLE,
                    "Состояние «" + name + "» повреждено или пусто."); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        int applied = applyStaticMarkPaths(dialog, panel, viewer, snap);
    }

    /**
     * Очистить все пометки → найти узлы по путям → статические галочки → {@code grayNodes}.
     * Проект для settings — только корень дерева (не {@code manager.getDtProject}:
     * при двух сторонах с одним именем он врёт и provider гасит галочки).
     */
    private static int applyStaticMarkPaths(
            Object dialog, Object panel, CheckboxTreeViewer viewer, FilterPresetStore.PresetSnapshot snap)
    {
        if (snap == null)
            return 0;
        Object settings = Global.getField(panel, "currentFilterSettings"); //$NON-NLS-1$
        if (settings == null)
            settings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        Object dialogSettings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        clearCompareSideMarkLists(dialog);
        clearViewerMarkCaches(panel, viewer);
        if (settings != null)
            clearAllMarksInSettings(settings);
        if (dialogSettings != null && dialogSettings != settings)
            clearAllMarksInSettings(dialogSettings);
        if (viewer != null && viewer.getTree() != null && !viewer.getTree().isDisposed())
            resetAllTreeItemChecks(viewer.getTree());

        applyIncludeFlagsQuiet(panel, settings, snap.includeSub, snap.includeParent);
        if (dialogSettings != null && dialogSettings != settings)
            applyIncludeFlagsQuiet(panel, dialogSettings, snap.includeSub, snap.includeParent);
        setBlacklistModeOnDialog(dialog, snap.blacklist);

        int applied = 0;
        if (viewer != null && viewer.getTree() != null && !viewer.getTree().isDisposed())
        {
            Object savedSide = dialog != null ? Global.getField(dialog, "side") : null; //$NON-NLS-1$
            boolean compare = isCompareFilterDialog(dialog);
            try
            {
                for (String path : snap.paths)
                {
                    // Навигатор: из compare-пресета только MAIN (имя проекта), OTHER не натягивать.
                    if (!compare && isPresetOtherPath(path))
                    {
                        continue;
                    }
                    TreeItem item = findTreeItemByMarkPath(dialog, viewer, path);
                    if (item == null || item.isDisposed())
                    {
                        continue;
                    }
                    Object element = item.getData();
                    IDtProject rootDt = rootDtProjectOfTreeItem(item);
                    if (element == null || rootDt == null)
                    {
                        TreeItem rootItem = item;
                        while (rootItem != null && !rootItem.isDisposed()
                                && rootItem.getParentItem() != null)
                            rootItem = rootItem.getParentItem();
                        Object rootData = rootItem != null && !rootItem.isDisposed()
                                ? rootItem.getData() : null;
                        continue;
                    }
                    // Голый корень (Конфигурация / OTHER) — не статика подсистем.
                    // Старый пресет с путём «OTHER» иначе включает checkedProjects.
                    if (element instanceof IDtProject || element instanceof IProject)
                    {
                        continue;
                    }
                    ComparisonSide side = sideForTreeRoot(dialog, rootDt);
                    if (dialog != null && side != null)
                        Global.setField(dialog, "side", side); //$NON-NLS-1$

                    if (settings != null)
                        applyElementMarkWithRoot(settings, rootDt, element);
                    if (dialogSettings != null && dialogSettings != settings)
                        applyElementMarkWithRoot(dialogSettings, rootDt, element);

                    item.setChecked(true);
                    item.setGrayed(false);
                    syncCheckedStateSets(panel, element, true);
                    addToCompareSideCheckedList(dialog, side, element);
                    applied++;
                }
            }
            finally
            {
                if (dialog != null)
                    Global.setField(dialog, "side", //$NON-NLS-1$
                            savedSide != null ? savedSide : ComparisonSide.OTHER);
            }
            // Корни unchecked: иначе grayNodes/setState(root) → setProjectChecked.
            // Не зовём штатный grayNodes: он обходит всё дерево через getChildren и
            // setSubtreeCheckGrayed — секунды и раскрытие узлов на больших конфигурациях.
            uncheckTreeRoots(viewer);
            clearProjectFullChecks(settings);
            if (dialogSettings != null && dialogSettings != settings)
                clearProjectFullChecks(dialogSettings);
            markStaticAncestorsPartial(viewer);
            forcePartialRootMarks(viewer, settings);
        }
        Global.invokeVoid(panel, "changeActionEnable"); //$NON-NLS-1$
        forceDeselectAllEnabled(panel);
        if (panel instanceof Composite panelComposite && !panelComposite.isDisposed())
        {
            Button blacklistBox = findBlacklistCheckbox(panelComposite);
            if (blacklistBox != null && !blacklistBox.isDisposed())
                blacklistBox.setSelection(snap.blacklist);
        }
        return applied;
    }

    private static void applyElementMarkWithRoot(Object settings, IDtProject rootDt, Object element)
    {
        if (settings == null || rootDt == null || element == null)
            return;
        ensureProjectInAllProjects(settings, rootDt);
        if (element instanceof IDtProject || element instanceof IProject)
        {
            setProjectMarkInSet(settings, "checkedProjects", rootDt, true); //$NON-NLS-1$
            return;
        }
        String className = element.getClass().getName();
        if (className.contains("AttachedNavigatorAdapter")) //$NON-NLS-1$
        {
            setProjectMarkInSet(settings, "includeNotIncludedInSubsystems", rootDt, true); //$NON-NLS-1$
            return;
        }
        if (className.contains("SubsystemNavigatorAdapter$Folder") //$NON-NLS-1$
                || className.contains("Folder")) //$NON-NLS-1$
            return;
        Object subsystem = unwrapSubsystemElement(element);
        if (subsystem == null)
        {
            return;
        }
        // Штатный API + прямая запись Long id (Boolean через invoke на (IDtProject,X,boolean) хрупкий).
        try
        {
            Global.invoke(settings, "setSubsystemChecked", rootDt, subsystem, Boolean.TRUE); //$NON-NLS-1$
        }
        catch (RuntimeException ex)
        {
        }
        setSubsystemCheckedDirect(settings, rootDt, subsystem, true);
    }

    /** Subsystem EObject или из адаптера; не Folder. */
    private static Object unwrapSubsystemElement(Object element)
    {
        if (element == null)
            return null;
        String cn = element.getClass().getName();
        if (cn.contains("Folder")) //$NON-NLS-1$
            return null;
        if (cn.contains("Subsystem") && !cn.contains("Adapter")) //$NON-NLS-1$ //$NON-NLS-2$
            return element;
        // Адаптер/обёртка — достать EObject.
        for (String method : new String[] { "getSubsystem", "getEObject", "getModel", "getAdaptedelement", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "getAdaptedElement", "getTarget" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Object inner = Global.invoke(element, method);
            if (inner != null && inner != element)
            {
                String icn = inner.getClass().getName();
                if (icn.contains("Subsystem") && !icn.contains("Folder") && !icn.contains("Adapter")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return inner;
            }
        }
        Object id = Global.invoke(element, "bmGetId"); //$NON-NLS-1$
        if (id instanceof Number)
            return element;
        return null;
    }

    private static IDtProject rootDtProjectOfTreeItem(TreeItem item)
    {
        TreeItem root = item;
        while (root != null && !root.isDisposed() && root.getParentItem() != null)
            root = root.getParentItem();
        if (root == null || root.isDisposed())
            return null;
        // Навигатор: корень часто IProject, не IDtProject.
        return toDtProjectElement(root.getData());
    }

    private static ComparisonSide sideForTreeRoot(Object dialog, IDtProject rootDt)
    {
        if (rootDt == null)
            return null;
        IDtProject otherDt = resolveCompareSideDtProject(dialog, ComparisonSide.OTHER);
        if (otherDt != null && rootDt == otherDt)
            return ComparisonSide.OTHER;
        IDtProject mainDt = resolveCompareSideDtProject(dialog, ComparisonSide.MAIN);
        if (mainDt != null && rootDt == mainDt)
            return ComparisonSide.MAIN;
        return ComparisonSide.MAIN;
    }

    private static void clearProjectFullChecks(Object settings)
    {
        if (settings == null)
            return;
        Object checked = Global.getField(settings, "checkedProjects"); //$NON-NLS-1$
        if (checked instanceof Set<?> set)
            set.clear();
    }

    private static void uncheckTreeRoots(CheckboxTreeViewer viewer)
    {
        if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
            return;
        for (TreeItem root : viewer.getTree().getItems())
        {
            if (root == null || root.isDisposed())
                continue;
            root.setChecked(false);
            root.setGrayed(false);
            Object data = root.getData();
            if (data != null)
            {
                viewer.setChecked(data, false);
                viewer.setGrayed(data, false);
            }
        }
    }

    /**
     * Частичная пометка предков статических узлов (checked+grayed) без
     * {@code setSubtreeCheckGrayed} / обхода потомков.
     */
    private static void markStaticAncestorsPartial(CheckboxTreeViewer viewer)
    {
        if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
            return;
        for (TreeItem root : viewer.getTree().getItems())
            markStaticAncestorsPartialRec(viewer, root);
    }

    private static void markStaticAncestorsPartialRec(CheckboxTreeViewer viewer, TreeItem item)
    {
        if (item == null || item.isDisposed())
            return;
        if (item.getChecked() && !item.getGrayed())
        {
            for (TreeItem parent = item.getParentItem(); parent != null && !parent.isDisposed();
                    parent = parent.getParentItem())
            {
                parent.setChecked(true);
                parent.setGrayed(true);
                Object data = parent.getData();
                if (data != null)
                {
                    viewer.setChecked(data, true);
                    viewer.setGrayed(data, true);
                }
            }
        }
        for (TreeItem child : item.getItems())
            markStaticAncestorsPartialRec(viewer, child);
    }

    /**
     * Если у корня есть статические id подсистем и нет {@code isProjectChecked} —
     * принудительно частичная (checked+grayed). Страховка после load.
     */
    private static void forcePartialRootMarks(CheckboxTreeViewer viewer, Object settings)
    {
        if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
            return;
        for (TreeItem root : viewer.getTree().getItems())
        {
            if (root == null || root.isDisposed())
                continue;
            Object data = root.getData();
            IDtProject dt = toDtProjectElement(data);
            if (dt == null)
                continue;
            if (settings != null && Boolean.TRUE.equals(
                    Global.invoke(settings, "isProjectChecked", dt))) //$NON-NLS-1$
                continue;
            if (countSubsystemIdsForProject(settings, dt) <= 0)
                continue;
            root.setChecked(true);
            root.setGrayed(true);
            if (data != null)
            {
                viewer.setChecked(data, true);
                viewer.setGrayed(data, true);
            }
        }
    }


    private static int countSubsystemIdsForProject(Object settings, IDtProject dt)
    {
        if (settings == null || dt == null)
            return 0;
        Object idsObj = Global.invoke(settings, "getCheckedSubsystemIds", dt); //$NON-NLS-1$
        return idsObj instanceof Set<?> set ? set.size() : 0;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void addToCompareSideCheckedList(Object dialog, ComparisonSide side, Object element)
    {
        if (dialog == null || side == null || element == null)
            return;
        String field = side == ComparisonSide.MAIN
                ? "mainCheckedElements" : "otherCheckedElements"; //$NON-NLS-1$ //$NON-NLS-2$
        Object listObj = Global.getField(dialog, field);
        if (listObj instanceof List list && !list.contains(element))
            list.add(element);
    }

    private static TreeItem findTreeItemByMarkPath(
            Object dialog, CheckboxTreeViewer viewer, String path)
    {
        if (path == null || path.isBlank() || viewer == null)
            return null;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return null;
        String[] segments = path.split("\\.", -1); //$NON-NLS-1$
        if (segments.length == 0)
            return null;
        // Раскрываем только чтобы получить TreeItem; после поиска сворачиваем обратно.
        List<Object> expandedForLookup = new ArrayList<>();
        TreeItem[] level = tree.getItems();
        TreeItem match = null;
        for (int i = 0; i < segments.length; i++)
        {
            match = findTreeItemMatchingSegment(dialog, viewer, level, segments[i], i == 0);
            if (match == null)
            {
                collapseExpandedForLookup(viewer, expandedForLookup);
                return null;
            }
            if (i < segments.length - 1)
            {
                Object data = match.getData();
                if (data != null && !viewer.getExpandedState(data))
                {
                    viewer.setExpandedState(data, true);
                    expandedForLookup.add(data);
                }
                level = match.getItems();
            }
        }
        collapseExpandedForLookup(viewer, expandedForLookup);
        return match;
    }

    private static void collapseExpandedForLookup(CheckboxTreeViewer viewer, List<Object> expanded)
    {
        if (viewer == null || expanded == null || expanded.isEmpty())
            return;
        for (int i = expanded.size() - 1; i >= 0; i--)
        {
            Object data = expanded.get(i);
            if (data != null)
                viewer.setExpandedState(data, false);
        }
    }

    private static Object findTreeElementByMarkPath(
            Object dialog, CheckboxTreeViewer viewer, String path)
    {
        TreeItem item = findTreeItemByMarkPath(dialog, viewer, path);
        return item != null && !item.isDisposed() ? item.getData() : null;
    }

    private static TreeItem findTreeItemMatchingSegment(
            Object dialog, CheckboxTreeViewer viewer, TreeItem[] items, String segment, boolean root)
    {
        if (items == null || segment == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (root)
            {
                if (matchesPresetRoot(dialog, item, segment))
                    return item;
            }
            else if (segment.equals(item.getText()))
                return item;
        }
        return null;
    }

    private static boolean matchesPresetRoot(Object dialog, TreeItem item, String segment)
    {
        Object data = item.getData();
        if (PRESET_OTHER_ROOT.equals(segment))
        {
            IDtProject otherDt = resolveCompareSideDtProject(dialog, ComparisonSide.OTHER);
            if (otherDt != null && (data == otherDt || dataEqualsDt(data, otherDt)))
                return true;
            // Только сравнение: второй корень. В навигаторе OTHER резолвится иначе.
            if (!isCompareFilterDialog(dialog))
                return false;
            Tree tree = item.getParent();
            if (tree != null && !tree.isDisposed())
            {
                TreeItem[] tops = tree.getItems();
                if (tops != null && tops.length >= 2 && item == tops[1])
                    return true;
            }
            return false;
        }
        if (data instanceof IDtProject dt)
            return segment.equals(dt.getName());
        if (data instanceof IProject project)
            return segment.equals(project.getName());
        IDtProject adapted = toDtProjectElement(data);
        if (adapted != null)
            return segment.equals(adapted.getName());
        return segment.equals(item.getText());
    }

    private static boolean dataEqualsDt(Object data, IDtProject dt)
    {
        if (data == null || dt == null)
            return false;
        if (data == dt)
            return true;
        IDtProject adapted = toDtProjectElement(data);
        return adapted != null && adapted == dt;
    }

    private static boolean isPresetOtherPath(String path)
    {
        if (path == null || path.isBlank())
            return false;
        return PRESET_OTHER_ROOT.equals(path)
                || path.startsWith(PRESET_OTHER_ROOT + "."); //$NON-NLS-1$
    }


    /**
     * Снимок пометок из дерева в settings для пресета.
     * Только статические (не серые) пометки — динамические после загрузки
     * восстановит {@code grayNodes()} по флагам include*.
     * Сначала штатный {@code saveFilterSettings}; если упал — ручной sync.
     */
    private static Object syncFilterSettingsFromViewer(
            Object dialog, Object panel, CheckboxTreeViewer viewer)
    {
        try
        {
            Global.invokeVoid(panel, "saveFilterSettings"); //$NON-NLS-1$
            Object filterSettings = Global.getField(panel, "filterSettings"); //$NON-NLS-1$
            if (filterSettings == null)
                filterSettings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
            if (filterSettings != null)
            {
                Object current = Global.getField(panel, "currentFilterSettings"); //$NON-NLS-1$
                if (current != null && current != filterSettings)
                    copyFilterMarks(filterSettings, current);
                return filterSettings;
            }
        }
        catch (RuntimeException ex)
        {
        }

        Object settings = Global.getField(panel, "currentFilterSettings"); //$NON-NLS-1$
        if (settings == null)
            settings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        if (settings == null)
            return null;

        boolean syncedFromViewer = false;
        if (viewer != null && viewer.getTree() != null && !viewer.getTree().isDisposed())
        {
            try
            {
                clearAllMarksInSettings(settings);
                Object[] checked = viewer.getCheckedElements();
                if (checked != null)
                {
                    for (Object element : checked)
                    {
                        if (element == null)
                            continue;
                        // Динамические (серые) пометки не сохраняем.
                        if (viewer.getGrayed(element))
                            continue;
                        applyCheckedElementToSettings(dialog, panel, settings, element);
                    }
                }
                syncedFromViewer = true;
            }
            catch (RuntimeException ex)
            {
            }
        }
        if (!syncedFromViewer)
        {
            try
            {
                Global.invokeVoid(panel, "saveFilterSettings"); //$NON-NLS-1$
                Object fromPanel = Global.getField(panel, "filterSettings"); //$NON-NLS-1$
                if (fromPanel != null)
                    settings = fromPanel;
            }
            catch (RuntimeException ex)
            {
            }
        }
        else
        {
            // Держим filterSettings в том же состоянии, что и working-копия.
            Object filterSettings = Global.getField(panel, "filterSettings"); //$NON-NLS-1$
            if (filterSettings != null && filterSettings != settings)
            {
                try
                {
                    copyFilterMarks(settings, filterSettings);
                }
                catch (RuntimeException ex)
                {
                }
            }
            Object dialogSettings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
            if (dialogSettings != null && dialogSettings != settings
                    && dialogSettings != filterSettings)
            {
                try
                {
                    copyFilterMarks(settings, dialogSettings);
                }
                catch (RuntimeException ex)
                {
                }
            }
        }
        return settings;
    }

    /**
     * Прямая очистка полей: {@code addProject()} всегда чекает проект, а
     * {@code Global.invoke(..., Boolean.FALSE)} для {@code (IDtProject, boolean)}
     * часто молча не срабатывает — после copy оставались старые/все галочки.
     */
    @SuppressWarnings("unchecked")
    private static void clearAllMarksInSettings(Object settings)
    {
        if (settings == null)
            return;
        Object checked = Global.getField(settings, "checkedProjects"); //$NON-NLS-1$
        if (checked instanceof Set<?> set)
            set.clear();
        Object includeNot = Global.getField(settings, "includeNotIncludedInSubsystems"); //$NON-NLS-1$
        if (includeNot instanceof Set<?> set)
            set.clear();
        Object mapObj = Global.getField(settings, "checkedSubsystems"); //$NON-NLS-1$
        if (mapObj instanceof Map<?, ?> map)
            map.clear();
    }

    private static int countCheckedSubsystemsInSettings(Object settings)
    {
        Object mapObj = Global.getField(settings, "checkedSubsystems"); //$NON-NLS-1$
        if (!(mapObj instanceof Map<?, ?> map))
            return -1;
        int count = 0;
        for (Object value : map.values())
        {
            if (value instanceof Set<?> set)
                count += set.size();
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static void ensureProjectInAllProjects(Object settings, Object project)
    {
        if (settings == null || project == null)
            return;
        Object all = Global.getField(settings, "allProjects"); //$NON-NLS-1$
        if (all instanceof Set<?>)
            ((Set<Object>) all).add(project);
    }

    @SuppressWarnings("unchecked")
    private static void setProjectMarkInSet(
            Object settings, String fieldName, Object project, boolean marked)
    {
        if (settings == null || project == null || fieldName == null)
            return;
        Object field = Global.getField(settings, fieldName);
        if (!(field instanceof Set<?>))
            return;
        Set<Object> set = (Set<Object>) field;
        if (marked)
            set.add(project);
        else
            set.remove(project);
    }

    private static void applyCheckedElementToSettings(
            Object dialog, Object panel, Object settings, Object element)
    {
        IDtProject project = resolveDtProjectForFilterItem(dialog, panel, element);
        if (element instanceof IDtProject || element instanceof IProject)
        {
            IDtProject root = project != null ? project : toDtProjectElement(element);
            if (root != null)
                setProjectMarkInSet(settings, "checkedProjects", root, true); //$NON-NLS-1$
            return;
        }
        String className = element.getClass().getName();
        if (className.contains("AttachedNavigatorAdapter")) //$NON-NLS-1$
        {
            if (project != null)
            {
                setProjectMarkInSet(settings, "includeNotIncludedInSubsystems", //$NON-NLS-1$
                        project, true);
            }
            return;
        }
        if (className.contains("SubsystemNavigatorAdapter$Folder")) //$NON-NLS-1$
            return;
        if (project != null && className.contains("mdclass.Subsystem")) //$NON-NLS-1$
            setSubsystemCheckedDirect(settings, project, element, true);
    }

    @SuppressWarnings("unchecked")
    private static void setSubsystemCheckedDirect(
            Object settings, IDtProject project, Object subsystem, boolean checked)
    {
        if (settings == null || project == null || subsystem == null)
            return;
        Object idObj = Global.invoke(subsystem, "bmGetId"); //$NON-NLS-1$
        if (idObj instanceof Number number)
            idObj = Long.valueOf(number.longValue());
        if (!(idObj instanceof Long))
        {
            Global.invoke(settings, "setSubsystemChecked", project, subsystem, //$NON-NLS-1$
                    Boolean.valueOf(checked));
            return;
        }
        Object mapObj = Global.getField(settings, "checkedSubsystems"); //$NON-NLS-1$
        if (!(mapObj instanceof Map<?, ?>))
            return;
        Map<Object, Set<Object>> map = (Map<Object, Set<Object>>) mapObj;
        if (checked)
            map.computeIfAbsent(project, key -> new java.util.HashSet<>()).add(idObj);
        else
        {
            Set<Object> ids = map.get(project);
            if (ids != null)
                ids.remove(idObj);
        }
    }

    private static IDtProject resolveDtProjectForFilterItem(
            Object dialog, Object panel, Object element)
    {
        IDtProject direct = toDtProjectElement(element);
        if (direct != null)
            return direct;
        Object manager = Global.getField(dialog != null ? dialog : panel,
                "filterBySubsystemsManager"); //$NON-NLS-1$
        if (manager == null)
            manager = Global.getField(panel, "filterBySubsystemsManager"); //$NON-NLS-1$
        if (manager != null && element != null
                && element.getClass().getName().contains("mdclass.Subsystem")) //$NON-NLS-1$
        {
            Object fromManager = Global.invoke(manager, "getDtProject", element); //$NON-NLS-1$
            if (fromManager instanceof IDtProject dt)
                return dt;
        }
        CheckboxTreeViewer viewer = resolveViewer(panel);
        if (viewer != null && viewer.getContentProvider() instanceof ITreeContentProvider provider)
        {
            Object current = element;
            for (int depth = 0; depth < 64 && current != null; depth++)
            {
                current = provider.getParent(current);
                IDtProject parent = toDtProjectElement(current);
                if (parent != null)
                    return parent;
            }
        }
        if (dialog != null)
        {
            Object adapterMap = Global.getField(dialog, "adapterParentMap"); //$NON-NLS-1$
            if (adapterMap instanceof Map<?, ?> map)
            {
                Object parent = map.get(element);
                IDtProject fromAdapter = toDtProjectElement(parent);
                if (fromAdapter != null)
                    return fromAdapter;
            }
        }
        return null;
    }

    private static boolean isCompareFilterDialog(Object dialog)
    {
        return dialog != null && Global.getField(dialog, "compareMergeProcessBatch") != null; //$NON-NLS-1$
    }

    /**
     * Git-сравнение конфигураций: в batch есть {@code GitComparisonDataSourceDescriptor}
     * (ревизия/коммит). Для таких OTHER в пресете обезличиваем.
     */
    private static boolean isGitCompareFilterDialog(Object dialog)
    {
        if (dialog == null)
            return false;
        Object batch = Global.getField(dialog, "compareMergeProcessBatch"); //$NON-NLS-1$
        if (batch == null)
            return false;
        Object descriptorsObj = Global.invoke(batch, "getDescriptors"); //$NON-NLS-1$
        if (!(descriptorsObj instanceof List<?> descriptors) || descriptors.isEmpty())
            return false;
        for (Object descriptor : descriptors)
        {
            if (descriptor == null)
                continue;
            String cn = descriptor.getClass().getName();
            if (cn.contains("GitComparisonDataSourceDescriptor")) //$NON-NLS-1$
                return true;
            // getRevision() есть только у git-дескриптора.
            Object revision = Global.invoke(descriptor, "getRevision"); //$NON-NLS-1$
            if (revision instanceof String s && !s.isBlank())
                return true;
        }
        Object manager = Global.getField(dialog, "comparisonManager"); //$NON-NLS-1$
        if (manager == null)
            return false;
        for (Object descriptor : descriptors)
        {
            if (descriptor == null)
                continue;
            Object handle = Global.invoke(descriptor, "getHandle"); //$NON-NLS-1$
            Object session = Global.invoke(manager, "getComparisonSession", handle); //$NON-NLS-1$
            if (session == null)
                continue;
            for (ComparisonSide side : new ComparisonSide[] { ComparisonSide.MAIN, ComparisonSide.OTHER })
            {
                Object dataSource = Global.invoke(session, "getDataSource", side); //$NON-NLS-1$
                if (dataSource == null)
                    continue;
                String cn = dataSource.getClass().getName();
                if (cn.contains(".git.") || cn.contains("Git")) //$NON-NLS-1$ //$NON-NLS-2$
                    return true;
            }
        }
        return false;
    }

    private static String resolveCompareSideProjectName(Object dialog, ComparisonSide side)
    {
        IDtProject dt = resolveCompareSideDtProject(dialog, side);
        if (dt == null)
            return null;
        String name = dt.getName();
        return name != null && !name.isEmpty() ? name : null;
    }

    /** IDtProject стороны сравнения из data source сессии (не из v8ProjectManager). */
    private static IDtProject resolveCompareSideDtProject(Object dialog, ComparisonSide side)
    {
        Object batch = Global.getField(dialog, "compareMergeProcessBatch"); //$NON-NLS-1$
        Object manager = Global.getField(dialog, "comparisonManager"); //$NON-NLS-1$
        if (batch == null || manager == null || side == null)
            return null;
        Object descriptorsObj = Global.invoke(batch, "getDescriptors"); //$NON-NLS-1$
        if (!(descriptorsObj instanceof List<?> descriptors) || descriptors.isEmpty())
            return null;
        for (Object descriptor : descriptors)
        {
            if (descriptor == null)
                continue;
            Object handle = Global.invoke(descriptor, "getHandle"); //$NON-NLS-1$
            Object session = Global.invoke(manager, "getComparisonSession", handle); //$NON-NLS-1$
            if (session == null)
                continue;
            Object dataSource = Global.invoke(session, "getDataSource", side); //$NON-NLS-1$
            if (dataSource == null)
                continue;
            Object dtProject = Global.invoke(dataSource, "getDtProject"); //$NON-NLS-1$
            if (dtProject instanceof IDtProject dt)
                return dt;
        }
        return null;
    }

    /** Имя стороны как в заголовке колонки редактора сравнения. */
    private static String resolveCompareSideDisplayName(Object dialog, ComparisonSide side)
    {
        if (dialog == null || side == null)
            return null;
        String field = side == ComparisonSide.MAIN
                ? "mainComparisonSideName" //$NON-NLS-1$
                : "otherComparisonSideName"; //$NON-NLS-1$
        Object name = Global.getField(dialog, field);
        if (name instanceof String s && !s.isEmpty())
            return s;
        return resolveCompareSideProjectName(dialog, side);
    }

    private static IDtProject toDtProjectElement(Object element)
    {
        if (element instanceof IDtProject dt)
            return dt;
        if (element instanceof IProject project)
        {
            Object adapted = Adapters.adapt(project, IDtProject.class);
            if (adapted instanceof IDtProject dt)
                return dt;
            return Global.getDtProjectFromWorkspaceProject(project);
        }
        return null;
    }

    private static ComparisonSide findCompareSideForProject(Object dialog, IDtProject dtProject)
    {
        Object batch = Global.getField(dialog, "compareMergeProcessBatch"); //$NON-NLS-1$
        Object manager = Global.getField(dialog, "comparisonManager"); //$NON-NLS-1$
        if (batch == null || manager == null || dtProject == null)
            return null;
        Object descriptorsObj = Global.invoke(batch, "getDescriptors"); //$NON-NLS-1$
        if (!(descriptorsObj instanceof List<?> descriptors))
            return null;
        for (ComparisonSide side : new ComparisonSide[] { ComparisonSide.MAIN, ComparisonSide.OTHER })
        {
            for (Object descriptor : descriptors)
            {
                if (descriptor == null)
                    continue;
                Object handle = Global.invoke(descriptor, "getHandle"); //$NON-NLS-1$
                Object session = Global.invoke(manager, "getComparisonSession", handle); //$NON-NLS-1$
                if (session == null)
                    continue;
                Object dataSource = Global.invoke(session, "getDataSource", side); //$NON-NLS-1$
                if (dataSource == null)
                    continue;
                Object dsProject = Global.invoke(dataSource, "getDtProject"); //$NON-NLS-1$
                if (dtProject.equals(dsProject))
                    return side;
            }
        }
        return null;
    }

    private static void copyFilterMarks(Object source, Object target)
    {
        if (source == null || target == null)
            return;
        // В навигаторе resolvePresetTargetSettings может вернуть тот же newSettings —
        // clear+return стирал уже загруженные пометки (лог: resolvedSubs=1 → apply srcSubsystems=0).
        if (source == target)
            return;
        clearAllMarksInSettings(target);
        Boolean includeSub = (Boolean) Global.invoke(source,
                "isIncludeObjectsFromSubordinateSubsystems"); //$NON-NLS-1$
        Boolean includeParent = (Boolean) Global.invoke(source,
                "isIncludeObjectsFromParentSubsystems"); //$NON-NLS-1$
        if (includeSub != null)
            Global.setField(target, "includeObjectsFromSubordinateSubsystems", includeSub); //$NON-NLS-1$
        if (includeParent != null)
            Global.setField(target, "includeObjectsFromParentSubsystems", includeParent); //$NON-NLS-1$

        Object allObj = Global.invoke(source, "getAllProjects"); //$NON-NLS-1$
        if (!(allObj instanceof Set<?> projects))
            return;
        for (Object project : projects)
        {
            if (project == null)
                continue;
            // Не addProject(): он всегда setProjectChecked(true) + includeNotIncluded(true).
            ensureProjectInAllProjects(target, project);
            boolean projectChecked = Boolean.TRUE.equals(
                    Global.invoke(source, "isProjectChecked", project)); //$NON-NLS-1$
            setProjectMarkInSet(target, "checkedProjects", project, projectChecked); //$NON-NLS-1$
            boolean includeNot = Boolean.TRUE.equals(Global.invoke(source,
                    "isIncludeNotIncludedInSubsystems", project)); //$NON-NLS-1$
            setProjectMarkInSet(target, "includeNotIncludedInSubsystems", //$NON-NLS-1$
                    project, includeNot);

            Object idsObj = Global.invoke(source, "getCheckedSubsystemIds", project); //$NON-NLS-1$
            if (!(idsObj instanceof Set<?> ids) || ids.isEmpty())
                continue;
            Object targetMap = Global.getField(target, "checkedSubsystems"); //$NON-NLS-1$
            if (!(targetMap instanceof Map<?, ?>))
                continue;
            @SuppressWarnings("unchecked")
            Map<Object, Set<Object>> map = (Map<Object, Set<Object>>) targetMap;
            Set<Object> targetIds = map.computeIfAbsent(project, key -> new java.util.HashSet<>());
            for (Object id : ids)
            {
                if (id != null)
                    targetIds.add(id);
            }
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void clearCompareSideMarkLists(Object dialog)
    {
        if (dialog == null)
            return;
        for (String field : new String[] {
                "mainCheckedElements", //$NON-NLS-1$
                "otherCheckedElements", //$NON-NLS-1$
                "mainVisibleItemData", //$NON-NLS-1$
                "otherVisibleItemData" }) //$NON-NLS-1$
        {
            Object listObj = Global.getField(dialog, field);
            if (listObj instanceof List list)
                list.clear();
        }
    }

    /**
     * Сброс кэшей пометок SWT/ImprovedCheckboxTreeViewer перед применением пресета —
     * иначе родительские {@code userModifiedElements} перекрывают новые настройки.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void clearViewerMarkCaches(Object panel, CheckboxTreeViewer viewer)
    {
        Object checkedField = Global.getField(panel, "checkedElements"); //$NON-NLS-1$
        Object grayedField = Global.getField(panel, "grayedElements"); //$NON-NLS-1$
        if (checkedField instanceof Set checkedSet)
            checkedSet.clear();
        if (grayedField instanceof Set grayedSet)
            grayedSet.clear();
        if (viewer == null)
            return;
        Object userModified = Global.getField(viewer, "userModifiedElements"); //$NON-NLS-1$
        if (userModified instanceof Map map)
            map.clear();
    }

    private static void resetAllTreeItemChecks(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        for (TreeItem item : tree.getItems())
            resetTreeItemChecksRecursive(item);
    }

    private static void resetTreeItemChecksRecursive(TreeItem item)
    {
        if (item == null || item.isDisposed())
            return;
        item.setChecked(false);
        item.setGrayed(false);
        for (TreeItem child : item.getItems())
            resetTreeItemChecksRecursive(child);
    }

    private static void reapplyTreeChecksFromProvider(CheckboxTreeViewer viewer)
    {
        if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
            return;
        org.eclipse.jface.viewers.ICheckStateProvider provider = resolveCheckStateProvider(viewer);
        if (provider == null)
            return;
        for (TreeItem item : viewer.getTree().getItems())
            reapplyTreeItemCheckRecursive(item, provider);
    }

    private static org.eclipse.jface.viewers.ICheckStateProvider resolveCheckStateProvider(
            CheckboxTreeViewer viewer)
    {
        Object customized = Global.getField(viewer, "customizedCheckStateProvider"); //$NON-NLS-1$
        if (customized != null)
        {
            Object delegate = Global.getField(customized, "delegate"); //$NON-NLS-1$
            if (delegate instanceof org.eclipse.jface.viewers.ICheckStateProvider checkProvider)
                return checkProvider;
        }
        Object provider = Global.invoke(viewer, "getCheckStateProvider"); //$NON-NLS-1$
        return provider instanceof org.eclipse.jface.viewers.ICheckStateProvider checkProvider
                ? checkProvider : null;
    }

    private static void reapplyTreeItemCheckRecursive(
            TreeItem item, org.eclipse.jface.viewers.ICheckStateProvider provider)
    {
        if (item == null || item.isDisposed())
            return;
        Object data = item.getData();
        if (data != null)
        {
            boolean checked = provider.isChecked(data);
            boolean grayed = provider.isGrayed(data);
            item.setChecked(checked);
            item.setGrayed(grayed);
        }
        for (TreeItem child : item.getItems())
            reapplyTreeItemCheckRecursive(child, provider);
    }

    private static int countCheckedProjectsInSettings(Object settings)
    {
        Object all = Global.invoke(settings, "getAllProjects"); //$NON-NLS-1$
        if (!(all instanceof Set<?> projects))
            return -1;
        int count = 0;
        for (Object project : projects)
        {
            if (Boolean.TRUE.equals(Global.invoke(settings, "isProjectChecked", project))) //$NON-NLS-1$
                count++;
        }
        return count;
    }

    private static int countAllProjectsInSettings(Object settings)
    {
        Object all = Global.invoke(settings, "getAllProjects"); //$NON-NLS-1$
        return all instanceof Set<?> set ? set.size() : -1;
    }

    private static void syncIncludeCheckboxes(
            Object dialog, Object panel, boolean includeSub, boolean includeParent)
    {
        Object working = Global.getField(panel, "currentFilterSettings"); //$NON-NLS-1$
        applyIncludeFlagsQuiet(panel, working, includeSub, includeParent);
    }

    /**
     * Выставляет флаги include* без {@code AbstractSubsystemsPanel.setInclude*} —
     * штатные сеттеры зовут {@code grayNodes()}, который по уже отмеченным TreeItem
     * делает {@code setState(true)} и снова заполняет {@code currentFilterSettings}.
     */
    private static void applyIncludeFlagsQuiet(
            Object panel, Object settings, boolean includeSub, boolean includeParent)
    {
        if (settings != null)
        {
            Global.setField(settings, "includeObjectsFromSubordinateSubsystems", //$NON-NLS-1$
                    Boolean.valueOf(includeSub));
            Global.setField(settings, "includeObjectsFromParentSubsystems", //$NON-NLS-1$
                    Boolean.valueOf(includeParent));
        }
        if (!(panel instanceof Composite panelComposite) || panelComposite.isDisposed())
            return;
        Composite host = panelComposite.getParent();
        if (host == null || host.isDisposed())
            return;
        List<Button> nativeChecks = new ArrayList<>();
        for (Control child : host.getChildren())
        {
            if (child instanceof Button button && !button.isDisposed()
                    && (button.getStyle() & SWT.CHECK) != 0
                    && !Boolean.TRUE.equals(button.getData(OUR_CHECKBOX_KEY)))
                nativeChecks.add(button);
        }
        if (nativeChecks.size() > 0)
            nativeChecks.get(0).setSelection(includeSub);
        if (nativeChecks.size() > 1)
            nativeChecks.get(1).setSelection(includeParent);
    }

    /** Штатный grayNodes — динамические (серые) пометки по уже выставленным статическим. */
    private static void invokeGrayNodes(Object panel)
    {
        if (panel == null)
            return;
        try
        {
            Global.invokeVoid(panel, "grayNodes"); //$NON-NLS-1$
        }
        catch (RuntimeException ex)
        {
        }
    }

    private static int countIncludeNotInSettings(Object settings)
    {
        Object field = Global.getField(settings, "includeNotIncludedInSubsystems"); //$NON-NLS-1$
        return field instanceof Set<?> set ? set.size() : -1;
    }

    private static int countTreeChecked(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return -1;
        int[] count = { 0 };
        for (TreeItem item : tree.getItems())
            countTreeCheckedRecursive(item, count);
        return count[0];
    }

    private static void countTreeCheckedRecursive(TreeItem item, int[] count)
    {
        if (item == null || item.isDisposed())
            return;
        if (item.getChecked())
            count[0]++;
        for (TreeItem child : item.getItems())
            countTreeCheckedRecursive(child, count);
    }

    private static int countProviderCheckedRoots(CheckboxTreeViewer viewer)
    {
        if (viewer == null)
            return -1;
        org.eclipse.jface.viewers.ICheckStateProvider provider = resolveCheckStateProvider(viewer);
        if (provider == null || viewer.getTree() == null || viewer.getTree().isDisposed())
            return -1;
        int count = 0;
        for (TreeItem item : viewer.getTree().getItems())
        {
            Object data = item.getData();
            if (data != null && provider.isChecked(data))
                count++;
        }
        return count;
    }

    private static Shell dialogShell(Object dialog)
    {
        Object shellObj = Global.invoke(dialog, "getShell"); //$NON-NLS-1$
        return shellObj instanceof Shell shell && !shell.isDisposed() ? shell : null;
    }

    private static boolean isFullyChecked(CheckboxTreeViewer viewer, Object element)
    {
        return viewer.getChecked(element) && !viewer.getGrayed(element);
    }

    /** Кликнутый узел, для которого перехватываем штатный setState (не служебные). */
    private static boolean isMarkTarget(Object element)
    {
        if (element == null)
            return false;
        if (element instanceof IProject || element instanceof IDtProject)
            return false;
        String name = element.getClass().getName();
        if (name.contains("AttachedNavigatorAdapter")) //$NON-NLS-1$
            return false;
        if (name.contains("SubsystemNavigatorAdapter$Folder")) //$NON-NLS-1$
            return false;
        return true;
    }

    /** Корень проекта / папка подсистем — штатный setState сам ставит серую пометку. */
    private static boolean isProjectOrFolderParent(Object parent)
    {
        if (parent == null)
            return false;
        if (parent instanceof IProject || parent instanceof IDtProject)
            return true;
        String name = parent.getClass().getName();
        return name.contains("SubsystemNavigatorAdapter$Folder") //$NON-NLS-1$
            || name.contains("IWorkspaceRoot") //$NON-NLS-1$
            || name.contains("WorkspaceRoot"); //$NON-NLS-1$
    }

    /**
     * Дети, которые должны быть помечены для «полной» пометки родителя.
     * AttachedNavigatorAdapter («объекты вне подсистем») учитывается — иначе при одной
     * подсистеме родитель ошибочно поднимается до полной пометки.
     */
    private static boolean countsForParentCompletion(Object element)
    {
        if (element == null)
            return false;
        if (element instanceof IProject || element instanceof IDtProject)
            return false;
        String name = element.getClass().getName();
        return !name.contains("SubsystemNavigatorAdapter$Folder"); //$NON-NLS-1$
    }

    private static boolean isMarked(CheckboxTreeViewer viewer, Object element)
    {
        // После переоткрытия унаследованная пометка часто checked+grayed — это тоже «помечен».
        return viewer.getChecked(element);
    }

    private static boolean allMarkSiblingsMarked(
            CheckboxTreeViewer viewer, ITreeContentProvider provider, Object parent, Object except)
    {
        Object[] children = provider.getChildren(parent);
        if (children == null)
            return false;
        boolean any = false;
        for (Object child : children)
        {
            if (!countsForParentCompletion(child))
                continue;
            any = true;
            if (except != null && Objects.equals(child, except))
            {
                if (!isMarked(viewer, child))
                    return false;
                continue;
            }
            if (!isMarked(viewer, child))
                return false;
        }
        return any;
    }

    private static String markBrief(Object element)
    {
        if (element == null)
            return "null"; //$NON-NLS-1$
        String cn = element.getClass().getSimpleName();
        try
        {
            if (element instanceof IProject project)
                return cn + ":" + project.getName(); //$NON-NLS-1$
            if (element instanceof IDtProject dt)
                return cn + ":" + dt.getName(); //$NON-NLS-1$
            Object name = Global.invoke(element, "getName"); //$NON-NLS-1$
            if (name instanceof String s && !s.isEmpty())
                return cn + ":" + s; //$NON-NLS-1$
        }
        catch (RuntimeException ignored) {}
        return cn;
    }

    /**
     * К моменту listener TreeItem уже переключён SWT. Считаем «последним непомеченным»,
     * если остальные учитываемые братья уже помечены (в т.ч. серой/унаследованной пометкой).
     * Для корня проекта/папки не перехватываем — иначе срывается серая пометка «Конфигурация».
     */
    private static boolean isLastUncheckedChildNowChecked(
            CheckboxTreeViewer viewer, ITreeContentProvider provider, Object element)
    {
        if (!isMarkTarget(element) || !isMarked(viewer, element))
            return false;
        Object parent = provider.getParent(element);
        if (parent == null || isProjectOrFolderParent(parent))
            return false;
        return allMarkSiblingsMarked(viewer, provider, parent, element);
    }

    /**
     * Штатный setState при полностью помеченном родителе (✓ без серого) сносит поддерево
     * через setSubtreeChecked(parent, false). После переоткрытия так выглядит почти любой
     * родитель при «включать подчинённые» — перехватываем установку пометки ребёнку.
     */
    private static boolean wouldStaffResetParentSubtree(
            CheckboxTreeViewer viewer, ITreeContentProvider provider, Object element)
    {
        if (!isMarkTarget(element))
            return false;
        Object parent = provider.getParent(element);
        while (parent != null)
        {
            if (isFullyChecked(viewer, parent))
                return true;
            if (!viewer.getChecked(parent))
                return false;
            // серый предок — штатный код идёт выше
            parent = provider.getParent(parent);
        }
        return false;
    }

    /**
     * CTRL+клик по серой (унаследованной) пометке потомка при «включать подчинённые/родительские»:
     * штатный {@code setState} трактует клик как установку явной пометки ({@code checked=true}),
     * но предварительно делает {@code setSubtreeChecked} ближайшего полностью помеченного предка —
     * сносит пометки всего поддерева. Вместо этого материализуем в явные ({@code setNodeChecked})
     * серые пометки, лежащие в поддереве ближайшей статической пометки ({@code checked && !grayed})
     * кликнутого узла; серые пометки вне этого поддерева не трогаем. Пометку кликнутого узла снимаем.
     */
    private static void materializeGrayedMarks(
            Object panel, CheckboxTreeViewer viewer, Object clicked)
    {
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider provider))
        {
            FilterBySubsystemsDialogDebug.step("grayPromote", "no tree provider, skip"); //$NON-NLS-1$
            return;
        }
        Object staticMark = nearestStaticMarkAncestor(viewer, provider, clicked);
        if (staticMark == null)
        {
            FilterBySubsystemsDialogDebug.step("grayPromote", //$NON-NLS-1$
                "no static mark ancestor, skip clicked=" + markBrief(clicked)); //$NON-NLS-1$
            return;
        }
        List<Object> scope = new ArrayList<>();
        collectSubtree(provider, staticMark, scope);

        Object[] grayed = viewer.getGrayedElements();
        int explicitCount = 0;
        for (Object g : grayed)
        {
            if (g == null || g == clicked || !countsForParentCompletion(g) || !scope.contains(g))
                continue;
            Global.invokeVoid(panel, "setNodeChecked", g); //$NON-NLS-1$
            syncCheckedStateSets(panel, g, true);
            explicitCount++;
        }
        viewer.setGrayed(clicked, false);
        Global.invokeVoid(panel, "setNodeUnchecked", clicked); //$NON-NLS-1$
        syncCheckedStateSets(panel, clicked, false);
        // Статическая пометка — рассчитанная: третье состояние, если в поддереве остались
        // помеченные узлы; полное снятие, если помеченных не осталось (братьев у кликнутого нет).
        boolean staticGrayed = false;
        for (Object node : scope)
        {
            if (node != staticMark && viewer.getChecked(node))
            {
                staticGrayed = true;
                break;
            }
        }
        if (staticGrayed)
        {
            Global.invokeVoid(panel, "setNodeGrayed", staticMark); //$NON-NLS-1$
            syncCheckedStateSets(panel, staticMark, false);
        }
        else
        {
            Global.invokeVoid(panel, "setNodeUnchecked", staticMark); //$NON-NLS-1$
            syncCheckedStateSets(panel, staticMark, false);
        }
        Global.invokeVoid(panel, "changeActionEnable"); //$NON-NLS-1$
        forceDeselectAllEnabled(panel);
        FilterBySubsystemsDialogDebug.step("grayPromote", //$NON-NLS-1$
            "static=" + markBrief(staticMark) //$NON-NLS-1$
                + " staticState=" + (staticGrayed ? "grayed" : "unchecked") //$NON-NLS-1$ //$NON-NLS-2$
                + " scope=" + scope.size() //$NON-NLS-1$
                + " explicit=" + explicitCount //$NON-NLS-1$
                + " cleared=" + markBrief(clicked)); //$NON-NLS-1$
        Global.tempLog("subsystems-mark", //$NON-NLS-1$
            "materialize clicked=" + markBrief(clicked) //$NON-NLS-1$
                + " staticMark=" + markBrief(staticMark) //$NON-NLS-1$
                + " grayed=" + (grayed != null ? grayed.length : -1) //$NON-NLS-1$
                + " explicit=" + explicitCount); //$NON-NLS-1$
    }

    /** Ближайшая статическая пометка ({@code checked && !grayed}) среди предков элемента. */
    private static Object nearestStaticMarkAncestor(
            CheckboxTreeViewer viewer, ITreeContentProvider provider, Object element)
    {
        Object current = element;
        while (current != null)
        {
            if (isFullyChecked(viewer, current))
                return current;
            current = provider.getParent(current);
        }
        return null;
    }

    private static void markNodeAndPromoteParents(
            Object panel, CheckboxTreeViewer viewer, ITreeContentProvider provider, Object element)
    {
        Global.invokeVoid(panel, "setNodeChecked", element); //$NON-NLS-1$
        syncCheckedStateSets(panel, element, true);

        Object parent = provider.getParent(element);
        while (parent != null)
        {
            // Корень проекта не поднимаем до полной пометки — только серая при частичном выборе.
            if (isProjectOrFolderParent(parent))
            {
                if (!allMarkSiblingsMarked(viewer, provider, parent, null)
                    && isFullyChecked(viewer, parent))
                {
                    Global.invokeVoid(panel, "setNodeGrayed", parent); //$NON-NLS-1$
                    syncCheckedStateSets(panel, parent, false);
                }
                break;
            }
            if (!allMarkSiblingsMarked(viewer, provider, parent, null))
            {
                if (isFullyChecked(viewer, parent))
                {
                    Global.invokeVoid(panel, "setNodeGrayed", parent); //$NON-NLS-1$
                    syncCheckedStateSets(panel, parent, false);
                }
                break;
            }
            Global.invokeVoid(panel, "setNodeChecked", parent); //$NON-NLS-1$
            syncCheckedStateSets(panel, parent, true);
            parent = provider.getParent(parent);
        }
        Global.invokeVoid(panel, "changeActionEnable"); //$NON-NLS-1$
        forceDeselectAllEnabled(panel);
    }

    /**
     * Перехват штатного check-listener: не давать {@code setState} сносить поддерево родителя
     * при установке пометки ребёнку (последний непомеченный / родитель уже полностью помечен).
     */
    private static final class LastChildCheckGuard implements ICheckStateListener
    {
        private final Object panel;
        private final CheckboxTreeViewer viewer;
        private final ICheckStateListener[] delegates;

        LastChildCheckGuard(Object panel, CheckboxTreeViewer viewer, ICheckStateListener[] delegates)
        {
            this.panel = panel;
            this.viewer = viewer;
            this.delegates = delegates;
        }

        @Override
        public void checkStateChanged(CheckStateChangedEvent event)
        {
            Object element = event.getElement();
            if (!event.getChecked() || element == null
                || !(viewer.getContentProvider() instanceof ITreeContentProvider provider))
            {
                if (!event.getChecked() && element != null
                    && isMarkTarget(element)
                    && viewer.getGrayed(element) && !viewer.getChecked(element)
                    && isCtrlClick(viewer))
                {
                    materializeGrayedMarks(panel, viewer, element);
                    clearCtrlClick(viewer);
                    return;
                }
                for (ICheckStateListener delegate : delegates)
                {
                    if (delegate != null)
                        delegate.checkStateChanged(event);
                }
                return;
            }

            Object parent = provider.getParent(element);
            boolean last = isLastUncheckedChildNowChecked(viewer, provider, element);
            boolean reset = wouldStaffResetParentSubtree(viewer, provider, element);
            StringBuilder kids = new StringBuilder();
            Object[] children = parent != null ? provider.getChildren(parent) : null;
            if (children != null)
            {
                for (Object child : children)
                {
                    if (kids.length() > 0)
                        kids.append(',');
                    kids.append(markBrief(child))
                        .append(isMarked(viewer, child) ? '+' : '-');
                }
            }
            Global.tempLog("subsystems-mark", //$NON-NLS-1$
                "el=" + markBrief(element) //$NON-NLS-1$
                    + " parent=" + markBrief(parent) //$NON-NLS-1$
                    + " parentClass=" + (parent != null ? parent.getClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                    + " projectParent=" + isProjectOrFolderParent(parent) //$NON-NLS-1$
                    + " last=" + last + " reset=" + reset //$NON-NLS-1$ //$NON-NLS-2$
                    + " kids=[" + kids + "]"); //$NON-NLS-1$ //$NON-NLS-2$

            if (last || reset)
            {
                markNodeAndPromoteParents(panel, viewer, provider, element);
                FilterBySubsystemsDialogDebug.step("lastChildCheck", "without subtree reset"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            for (ICheckStateListener delegate : delegates)
            {
                if (delegate != null)
                    delegate.checkStateChanged(event);
            }
        }
    }

    private static void collectSubtree(ITreeContentProvider provider, Object element, List<Object> result)
    {
        if (element == null)
            return;
        result.add(element);
        Object[] children = provider.getChildren(element);
        if (children == null)
            return;
        for (Object child : children)
            collectSubtree(provider, child, result);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void syncCheckedStateSets(Object panel, Object element, boolean checked)
    {
        Object checkedField = Global.getField(panel, "checkedElements"); //$NON-NLS-1$
        Object grayedField = Global.getField(panel, "grayedElements"); //$NON-NLS-1$
        if (!(checkedField instanceof Set) || !(grayedField instanceof Set))
            return;
        Set checkedSet = (Set) checkedField;
        Set grayedSet = (Set) grayedField;
        if (checked)
        {
            checkedSet.add(element);
            grayedSet.remove(element);
        }
        else
        {
            checkedSet.remove(element);
            grayedSet.remove(element);
        }
    }

    private static Object findProjectElement(Object[] roots, IProject target)
    {
        if (roots == null || target == null)
            return null;
        for (Object root : roots)
        {
            if (sameWorkspaceProject(root, target))
                return root;
        }
        return null;
    }

    private static boolean sameWorkspaceProject(Object element, IProject target)
    {
        if (element instanceof IProject project)
            return target.equals(project);
        if (element instanceof IDtProject dtProject)
        {
            IProject workspaceProject = dtProject.getWorkspaceProject();
            return workspaceProject != null && target.equals(workspaceProject);
        }
        return false;
    }

    private static IProject resolveNavigatorProject()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        IViewPart navigator = page.findView(Global.NAVIGATOR_VIEW_ID);
        if (navigator == null)
            return null;

        ISelection selection = navigator.getSite().getSelectionProvider().getSelection();
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
            return null;

        if (selection instanceof TreeSelection treeSelection)
        {
            TreePath[] paths = treeSelection.getPaths();
            if (paths.length > 0 && paths[0].getSegmentCount() > 0)
            {
                IProject fromPath = projectFromElement(paths[0].getSegment(0));
                if (fromPath != null)
                    return fromPath;
            }
        }
        return projectFromElement(structured.getFirstElement());
    }

    private static IProject projectFromElement(Object element)
    {
        if (element instanceof IProject project)
            return project;
        if (element instanceof IDtProject dtProject)
            return dtProject.getWorkspaceProject();
        if (element instanceof IResource resource)
            return resource.getProject();
        return Adapters.adapt(element, IProject.class);
    }

    private static boolean isFilterBySubsystemsShell(Shell shell)
    {
        if (resolveDialog(shell) != null)
            return true;
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE);
    }

    private static Object resolveDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;

        Object fromShell = resolveDialogOnWidget(shell);
        if (fromShell != null)
            return fromShell;

        return resolveDialogInComposite(shell);
    }

    private static Object resolveDialogInComposite(Composite root)
    {
        if (root == null || root.isDisposed())
            return null;

        Object onRoot = resolveDialogOnWidget(root);
        if (onRoot != null)
            return onRoot;

        for (Control child : root.getChildren())
        {
            if (child.isDisposed())
                continue;
            Object onChild = resolveDialogOnWidget(child);
            if (onChild != null)
                return onChild;
            if (child instanceof Composite composite)
            {
                Object nested = resolveDialogInComposite(composite);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static Object resolveDialogOnWidget(org.eclipse.swt.widgets.Widget widget)
    {
        if (widget == null || widget.isDisposed())
            return null;

        for (String key : new String[] { null, "org.eclipse.jface.window.Window", //$NON-NLS-1$ //$NON-NLS-2$
            "org.eclipse.jface.dialogs.Dialog.dialog" }) //$NON-NLS-1$
        {
            Object data = key == null ? widget.getData() : widget.getData(key);
            if (isFilterBySubsystemsDialog(data))
                return data;
        }
        return null;
    }

    private static boolean isFilterBySubsystemsDialog(Object data)
    {
        return data != null && data.getClass().getName().contains(DIALOG_CLASS);
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static Rectangle clampToMonitor(Display display, Rectangle bounds, int minWidth, int minHeight)
    {
        if (display == null || bounds == null)
            return bounds;

        Monitor monitor = display.getPrimaryMonitor();
        Point center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        for (Monitor candidate : display.getMonitors())
        {
            if (candidate.getBounds().contains(center))
            {
                monitor = candidate;
                break;
            }
        }

        Rectangle client = monitor.getClientArea();
        int width = Math.max(minWidth, Math.min(bounds.width, client.width));
        int height = Math.max(minHeight, Math.min(bounds.height, client.height));
        int x = bounds.x;
        int y = bounds.y;
        if (x + width > client.x + client.width)
            x = client.x + client.width - width;
        if (y + height > client.y + client.height)
            y = client.y + client.height - height;
        if (x < client.x)
            x = client.x;
        if (y < client.y)
            y = client.y;
        return new Rectangle(x, y, width, height);
    }

    /**
     * Подписи корней {@code IDtProject} в режиме сравнения — имена сторон редактора.
     */
    private static final class CompareSideRootStyledProvider implements IStyledLabelProvider
    {
        private final Object dialog;
        private final IStyledLabelProvider delegate;

        CompareSideRootStyledProvider(Object dialog, IStyledLabelProvider delegate)
        {
            this.dialog = dialog;
            this.delegate = delegate;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            IDtProject project = toDtProjectElement(element);
            if (project != null)
            {
                ComparisonSide side = findCompareSideForProject(dialog, project);
                String label = resolveCompareSideDisplayName(dialog, side);
                if (label != null && !label.isEmpty())
                    return new StyledString(label);
            }
            StyledString styled = delegate.getStyledText(element);
            return styled != null ? styled : new StyledString();
        }

        @Override
        public Image getImage(Object element)
        {
            return delegate.getImage(element);
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            delegate.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            delegate.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate.isLabelProperty(element, property);
        }

        @Override
        public void dispose()
        {
            delegate.dispose();
        }
    }

    /**
     * Подсветка совпадений smart-фильтра поверх штатного label provider дерева подсистем.
     * Матч строго по тексту самого узла ({@link SmartMatcher#matches}) — как выбрал пользователь
     * («только имя узла»), и подсветка красит только такие узлы.
     */
    private static final class SubsystemHighlightStyledProvider implements IStyledLabelProvider
    {
        private final IStyledLabelProvider delegate;
        private String highlightPattern = ""; //$NON-NLS-1$

        SubsystemHighlightStyledProvider(IStyledLabelProvider delegate)
        {
            this.delegate = delegate;
        }

        void setHighlightPattern(String pattern)
        {
            this.highlightPattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = delegate.getStyledText(element);
            if (styled == null)
                styled = new StyledString();
            if (highlightPattern.isEmpty())
                return styled;
            String text = styled.getString();
            if (text == null || text.isEmpty())
                return styled;
            SmartMatcher matcher = new SmartMatcher(highlightPattern);
            if (matcher.isEmpty || !matcher.matches(text))
                return styled;
            SmartMatchHighlight.applyRanges(styled, matcher.getHighlightRanges(text));
            return styled;
        }

        @Override
        public Image getImage(Object element)
        {
            return delegate.getImage(element);
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            delegate.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            delegate.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate.isLabelProperty(element, property);
        }

        @Override
        public void dispose()
        {
            delegate.dispose();
        }
    }

    /**
     * Фильтр дерева подсистем по {@link SmartMatcher}: узел виден, если матчится текст самого узла
     * или есть матчащийся потомок (как штатный {@code InMemorySearchFilter} — родители совпадений
     * не прячутся).
     */
    private static final class SmartSubsystemsFilter extends ViewerFilter
    {
        private final IStyledLabelProvider labelProvider;
        private SmartMatcher matcher;

        SmartSubsystemsFilter(IStyledLabelProvider labelProvider)
        {
            this.labelProvider = labelProvider;
            this.matcher = new SmartMatcher("");
        }

        void setPattern(String pattern)
        {
            this.matcher = new SmartMatcher(pattern);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            if (nodeMatches(element))
                return true;
            if (!(viewer instanceof StructuredViewer structuredViewer))
                return false;
            Object provider = structuredViewer.getContentProvider();
            if (provider instanceof ITreeContentProvider treeProvider)
                return hasMatchingDescendant(treeProvider, element);
            return false;
        }

        private boolean nodeMatches(Object element)
        {
            if (element == null)
                return false;
            String text = nodeText(element);
            return text != null && matcher.matches(text);
        }

        private String nodeText(Object element)
        {
            try
            {
                StyledString styled = labelProvider.getStyledText(element);
                return styled != null ? styled.getString() : null;
            }
            catch (RuntimeException ex)
            {
                return null;
            }
        }

        private boolean hasMatchingDescendant(ITreeContentProvider provider, Object element)
        {
            Object[] children = provider.getChildren(element);
            if (children == null)
                return false;
            for (Object child : children)
            {
                if (child == null)
                    continue;
                if (nodeMatches(child))
                    return true;
                if (hasMatchingDescendant(provider, child))
                    return true;
            }
            return false;
        }
    }

    /**
     * isChecked/isGrayed по Long-id в settings для корня дерева — обход бага
     * {@code manager.getDtProject} при двух IDtProject с одним именем.
     */
    private static final class BothSidesCheckStateProvider
            implements org.eclipse.jface.viewers.ICheckStateProvider
    {
        private final Object dialog;
        private final Object panel;
        private final CheckboxTreeViewer viewer;
        private final org.eclipse.jface.viewers.ICheckStateProvider delegate;

        BothSidesCheckStateProvider(Object dialog, Object panel, CheckboxTreeViewer viewer,
                org.eclipse.jface.viewers.ICheckStateProvider delegate)
        {
            this.dialog = dialog;
            this.panel = panel;
            this.viewer = viewer;
            this.delegate = delegate;
        }

        @Override
        public boolean isChecked(Object element)
        {
            if (element instanceof IDtProject dt)
            {
                Boolean top = projectTopValue(dt, true);
                if (top != null)
                    return top.booleanValue();
            }
            Boolean byRoot = elementValueByTreeRoot(element, true);
            if (byRoot != null)
                return byRoot.booleanValue();
            return delegate != null && delegate.isChecked(element);
        }

        @Override
        public boolean isGrayed(Object element)
        {
            if (element instanceof IDtProject dt)
            {
                // Полная галка проекта — не серая; иначе при подсистемах — серая (динамика).
                Object settings = settings();
                if (settings != null && Boolean.TRUE.equals(
                        Global.invoke(settings, "isProjectChecked", dt))) //$NON-NLS-1$
                    return false;
                if (countSubsystemIdsForProject(settings, dt) > 0)
                    return true;
                return delegate != null && delegate.isGrayed(element);
            }
            Boolean byRoot = elementValueByTreeRoot(element, false);
            if (byRoot != null)
                return byRoot.booleanValue();
            return delegate != null && delegate.isGrayed(element);
        }

        /** Как штатный getTopElementValue при include*: checked+grayed = частичная. */
        private Boolean projectTopValue(IDtProject dt, boolean forChecked)
        {
            Object settings = settings();
            if (settings == null || dt == null)
                return null;
            if (Boolean.TRUE.equals(Global.invoke(settings, "isProjectChecked", dt))) //$NON-NLS-1$
                return Boolean.valueOf(forChecked);
            boolean includeOn = Boolean.TRUE.equals(Global.invoke(settings,
                    "isIncludeObjectsFromSubordinateSubsystems")) //$NON-NLS-1$
                    || Boolean.TRUE.equals(Global.invoke(settings,
                            "isIncludeObjectsFromParentSubsystems")); //$NON-NLS-1$
            if (!includeOn)
                return Boolean.FALSE;
            if (Boolean.TRUE.equals(Global.invoke(settings,
                    "isIncludeNotIncludedInSubsystems", dt))) //$NON-NLS-1$
                return Boolean.TRUE;
            return Boolean.valueOf(countSubsystemIdsForProject(settings, dt) > 0);
        }

        private Object settings()
        {
            Object s = Global.getField(panel, "currentFilterSettings"); //$NON-NLS-1$
            if (s == null)
                s = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
            return s;
        }

        /**
         * Как штатный {@code getSubsystemElementValue}/{@code getAttachedElementValue}:
         * {@code forChecked=true} → isChecked, {@code false} → isGrayed.
         * Статика (id в settings) — checked без gray; наследники при include* — оба true.
         */
        private Boolean elementValueByTreeRoot(Object element, boolean forChecked)
        {
            if (element == null)
                return null;
            String cn = element.getClass().getName();
            if (cn.contains("Folder")) //$NON-NLS-1$
                return null;
            IDtProject root = findRootDtForElement(element);
            if (root == null)
                return null;
            Object settings = settings();
            if (settings == null)
                return null;
            if (Boolean.TRUE.equals(Global.invoke(settings, "isProjectChecked", root))) //$NON-NLS-1$
                return Boolean.TRUE;
            if (cn.contains("AttachedNavigatorAdapter")) //$NON-NLS-1$
            {
                boolean includeNot = Boolean.TRUE.equals(Global.invoke(settings,
                        "isIncludeNotIncludedInSubsystems", root)); //$NON-NLS-1$
                if (!includeNot)
                    return Boolean.FALSE;
                // includeNot — как статика на адаптере: checked, не gray.
                return Boolean.valueOf(forChecked);
            }
            if (cn.contains("Adapter") && !cn.contains("Subsystem")) //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            if (!cn.contains("Subsystem") && Global.invoke(element, "bmGetId") == null) //$NON-NLS-1$ //$NON-NLS-2$
                return null;

            Object idsObj = Global.invoke(settings, "getCheckedSubsystemIds", root); //$NON-NLS-1$
            Set<?> ids = idsObj instanceof Set<?> set ? set : null;
            boolean includeOn = Boolean.TRUE.equals(Global.invoke(settings,
                    "isIncludeObjectsFromSubordinateSubsystems")) //$NON-NLS-1$
                    || Boolean.TRUE.equals(Global.invoke(settings,
                            "isIncludeObjectsFromParentSubsystems")); //$NON-NLS-1$

            if (ids == null || ids.isEmpty())
                return Boolean.FALSE;

            if (idInCheckedSet(element, ids))
                return Boolean.valueOf(forChecked); // статика: grayed=false

            if (!includeOn)
                return Boolean.FALSE;

            // Динамика: только предки со статической пометкой (O(глубина)).
            // Потомков не обходим через getChildren — это секунды на больших деревьях;
            // серых предков после load выставляет markStaticAncestorsPartial.
            if (hasStaticMarkedAncestor(element, ids, root, settings))
                return Boolean.TRUE;
            return Boolean.FALSE;
        }

        private boolean idInCheckedSet(Object element, Set<?> ids)
        {
            Object idObj = Global.invoke(element, "bmGetId"); //$NON-NLS-1$
            if (idObj instanceof Number number)
                idObj = Long.valueOf(number.longValue());
            if (idObj == null)
                return false;
            for (Object id : ids)
            {
                Object normalized = id instanceof Number n ? Long.valueOf(n.longValue()) : id;
                if (idObj.equals(normalized))
                    return true;
            }
            return false;
        }

        private boolean hasStaticMarkedAncestor(
                Object element, Set<?> ids, IDtProject root, Object settings)
        {
            if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
                return false;
            TreeItem item = findTreeItem(viewer.getTree().getItems(), element);
            if (item == null)
                return false;
            for (TreeItem parent = item.getParentItem(); parent != null && !parent.isDisposed();
                    parent = parent.getParentItem())
            {
                Object data = parent.getData();
                if (data instanceof IDtProject dt)
                {
                    return Boolean.TRUE.equals(
                            Global.invoke(settings, "isProjectChecked", dt)); //$NON-NLS-1$
                }
                if (data != null && idInCheckedSet(data, ids))
                    return true;
            }
            return Boolean.TRUE.equals(
                    Global.invoke(settings, "isProjectChecked", root)); //$NON-NLS-1$
        }

        private IDtProject findRootDtForElement(Object element)
        {
            if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
                return null;
            TreeItem item = findTreeItem(viewer.getTree().getItems(), element);
            return item != null ? rootDtProjectOfTreeItem(item) : null;
        }
    }

    private static TreeItem findTreeItem(TreeItem[] items, Object element)
    {
        if (items == null || element == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (item.getData() == element)
                return item;
            TreeItem nested = findTreeItem(item.getItems(), element);
            if (nested != null)
                return nested;
        }
        return null;
    }

    /**
     * Корни дерева = проекты MAIN и OTHER (уникальные). Перед {@code getChildren} для
     * {@code IDtProject} выставляет {@code dialog.side}, иначе штатный
     * {@code getComparisonSession} не находит сессию.
     */
    private static final class BothSidesTreeContentProvider implements ITreeContentProvider
    {
        private final Object dialog;
        private final ITreeContentProvider delegate;

        BothSidesTreeContentProvider(Object dialog, ITreeContentProvider delegate)
        {
            this.dialog = dialog;
            this.delegate = delegate;
        }

        @Override
        public Object[] getElements(Object inputElement)
        {
            LinkedHashSet<Object> roots = new LinkedHashSet<>();
            Object saved = Global.getField(dialog, "side"); //$NON-NLS-1$
            try
            {
                for (ComparisonSide side : new ComparisonSide[] { ComparisonSide.MAIN, ComparisonSide.OTHER })
                {
                    Global.setField(dialog, "side", side); //$NON-NLS-1$
                    Object[] elements = delegate.getElements(inputElement);
                    if (elements == null)
                        continue;
                    for (Object element : elements)
                    {
                        if (element != null)
                            roots.add(element);
                    }
                }
            }
            finally
            {
                Global.setField(dialog, "side", //$NON-NLS-1$
                        saved != null ? saved : ComparisonSide.OTHER);
            }
            return roots.toArray();
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            return safeDelegateChildren(parentElement);
        }

        @Override
        public Object getParent(Object element)
        {
            return delegate.getParent(element);
        }

        @Override
        public boolean hasChildren(Object element)
        {
            alignDialogSideForElement(element);
            try
            {
                return delegate.hasChildren(element);
            }
            catch (RuntimeException ex)
            {
                if (!isNoValuePresent(ex))
                    throw ex;
                Object[] children = safeDelegateChildren(element);
                return children.length > 0;
            }
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
        {
            delegate.inputChanged(viewer, oldInput, newInput);
        }

        @Override
        public void dispose()
        {
            delegate.dispose();
        }

        private Object[] safeDelegateChildren(Object element)
        {
            alignDialogSideForElement(element);
            try
            {
                Object[] children = delegate.getChildren(element);
                return children != null ? children : new Object[0];
            }
            catch (RuntimeException ex)
            {
                if (!isNoValuePresent(ex))
                    throw ex;
                // side мог не совпасть с проектом узла — пробуем вторую сторону.
                Object saved = Global.getField(dialog, "side"); //$NON-NLS-1$
                ComparisonSide retry = saved == ComparisonSide.OTHER
                        ? ComparisonSide.MAIN : ComparisonSide.OTHER;
                Global.setField(dialog, "side", retry); //$NON-NLS-1$
                try
                {
                    Object[] children = delegate.getChildren(element);
                    return children != null ? children : new Object[0];
                }
                catch (RuntimeException ex2)
                {
                    return new Object[0];
                }
                finally
                {
                    if (saved != null)
                        Global.setField(dialog, "side", saved); //$NON-NLS-1$
                }
            }
        }

        private void alignDialogSideForElement(Object element)
        {
            IDtProject dtProject = toDtProjectElement(element);
            if (dtProject == null && element != null
                    && element.getClass().getName().contains("mdclass.Subsystem")) //$NON-NLS-1$
            {
                Object manager = Global.getField(dialog, "filterBySubsystemsManager"); //$NON-NLS-1$
                Object fromManager = manager != null
                        ? Global.invoke(manager, "getDtProject", element) : null; //$NON-NLS-1$
                if (fromManager instanceof IDtProject dt)
                    dtProject = dt;
            }
            if (dtProject == null)
            {
                Object current = element;
                for (int depth = 0; depth < 64 && current != null; depth++)
                {
                    current = delegate.getParent(current);
                    dtProject = toDtProjectElement(current);
                    if (dtProject != null)
                        break;
                }
            }
            if (dtProject == null)
                return;
            ComparisonSide matched = findCompareSideForProject(dialog, dtProject);
            if (matched != null)
                Global.setField(dialog, "side", matched); //$NON-NLS-1$
        }
    }

    private static boolean isNoValuePresent(Throwable ex)
    {
        for (Throwable t = ex; t != null; t = t.getCause())
        {
            if (t instanceof java.util.NoSuchElementException)
                return true;
            String message = t.getMessage();
            if (message != null && message.contains("No value present")) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    /**
     * Именованные снимки фильтра по подсистемам: XML через штатный
     * {@code IFilterBySubsystemsManager.saveState/loadState} + флаг чёрного списка.
     */
    /**
     * Именованные снимки: список путей статических пометок + флаги.
     * Формат v2 (текст → Base64), без штатного saveState/XML имён проектов.
     */
    private static final class FilterPresetStore
    {
        private static final String PREF_COUNT = "comfort.filterBySubsystems.presets.count"; //$NON-NLS-1$
        private static final String PREF_NAME_PREFIX = "comfort.filterBySubsystems.presets."; //$NON-NLS-1$
        private static final String SUFFIX_NAME = ".name"; //$NON-NLS-1$
        private static final String SUFFIX_XML = ".xml"; //$NON-NLS-1$
        private static final String MAGIC = "comfort.filter.preset.v2"; //$NON-NLS-1$

        private FilterPresetStore() {}

        static List<String> listNames()
        {
            IPreferenceStore store = preferenceStore();
            if (store == null)
                return List.of();
            int count = store.getInt(PREF_COUNT);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < count; i++)
            {
                String name = store.getString(PREF_NAME_PREFIX + i + SUFFIX_NAME);
                if (name != null && !name.isBlank())
                    names.add(name);
            }
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            return names;
        }

        static boolean contains(String name)
        {
            return indexOf(name) >= 0;
        }

        static void put(String name, PresetSnapshot snap)
        {
            IPreferenceStore store = preferenceStore();
            if (store == null)
                throw new IllegalStateException("PreferenceStore недоступен"); //$NON-NLS-1$
            String payload = serialize(snap);
            int index = indexOf(name);
            if (index < 0)
            {
                index = store.getInt(PREF_COUNT);
                store.setValue(PREF_COUNT, index + 1);
            }
            store.setValue(PREF_NAME_PREFIX + index + SUFFIX_NAME, name);
            store.setValue(PREF_NAME_PREFIX + index + SUFFIX_XML, payload);
            flushStore(store);
        }

        static PresetSnapshot load(String name)
        {
            IPreferenceStore store = preferenceStore();
            if (store == null)
                return null;
            int index = indexOf(name);
            if (index < 0)
                return null;
            String payload = store.getString(PREF_NAME_PREFIX + index + SUFFIX_XML);
            if (payload == null || payload.isBlank())
                return null;
            return deserialize(payload);
        }

        static void delete(String name)
        {
            IPreferenceStore store = preferenceStore();
            if (store == null)
                return;
            int index = indexOf(name);
            if (index < 0)
                return;
            int count = store.getInt(PREF_COUNT);
            LinkedHashMap<String, String> kept = new LinkedHashMap<>();
            for (int i = 0; i < count; i++)
            {
                if (i == index)
                    continue;
                String n = store.getString(PREF_NAME_PREFIX + i + SUFFIX_NAME);
                String xml = store.getString(PREF_NAME_PREFIX + i + SUFFIX_XML);
                if (n != null && !n.isBlank() && xml != null)
                    kept.put(n, xml);
            }
            for (int i = 0; i < count; i++)
            {
                store.setToDefault(PREF_NAME_PREFIX + i + SUFFIX_NAME);
                store.setToDefault(PREF_NAME_PREFIX + i + SUFFIX_XML);
            }
            int i = 0;
            for (Map.Entry<String, String> entry : kept.entrySet())
            {
                store.setValue(PREF_NAME_PREFIX + i + SUFFIX_NAME, entry.getKey());
                store.setValue(PREF_NAME_PREFIX + i + SUFFIX_XML, entry.getValue());
                i++;
            }
            store.setValue(PREF_COUNT, i);
            flushStore(store);
        }

        private static int indexOf(String name)
        {
            if (name == null || name.isBlank())
                return -1;
            IPreferenceStore store = preferenceStore();
            if (store == null)
                return -1;
            int count = store.getInt(PREF_COUNT);
            for (int i = 0; i < count; i++)
            {
                String n = store.getString(PREF_NAME_PREFIX + i + SUFFIX_NAME);
                if (name.equals(n))
                    return i;
            }
            return -1;
        }

        private static String serialize(PresetSnapshot snap)
        {
            StringBuilder sb = new StringBuilder();
            sb.append(MAGIC).append('\n');
            sb.append("blacklist=").append(snap.blacklist ? "1" : "0").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append("includeSub=").append(snap.includeSub ? "1" : "0").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append("includeParent=").append(snap.includeParent ? "1" : "0").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (snap.paths != null)
            {
                for (String p : snap.paths)
                {
                    if (p != null && !p.isBlank())
                        sb.append(p).append('\n');
                }
            }
            return Base64.getEncoder().encodeToString(
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        private static PresetSnapshot deserialize(String payload)
        {
            String raw = decodePayload(payload);
            if (!raw.startsWith(MAGIC))
                throw new IllegalStateException(
                        "Старый формат пресета — сохраните состояние заново"); //$NON-NLS-1$
            boolean blacklist = false;
            boolean includeSub = false;
            boolean includeParent = false;
            List<String> paths = new ArrayList<>();
            for (String line : raw.split("\n", -1)) //$NON-NLS-1$
            {
                String t = line.trim();
                if (t.isEmpty() || t.equals(MAGIC))
                    continue;
                if (t.startsWith("blacklist=")) //$NON-NLS-1$
                    blacklist = "1".equals(t.substring(10)); //$NON-NLS-1$
                else if (t.startsWith("includeSub=")) //$NON-NLS-1$
                    includeSub = "1".equals(t.substring(11)); //$NON-NLS-1$
                else if (t.startsWith("includeParent=")) //$NON-NLS-1$
                    includeParent = "1".equals(t.substring(14)); //$NON-NLS-1$
                else
                    paths.add(t);
            }
            return new PresetSnapshot(paths, blacklist, includeSub, includeParent);
        }

        private static String decodePayload(String payload)
        {
            String trimmed = payload.trim();
            if (trimmed.startsWith(MAGIC) || trimmed.startsWith("<")) //$NON-NLS-1$
                return trimmed;
            try
            {
                byte[] bytes = Base64.getDecoder().decode(trimmed);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            catch (IllegalArgumentException ex)
            {
                throw new IllegalStateException(
                        "Повреждённый снимок (пересохраните состояние фильтра)", ex); //$NON-NLS-1$
            }
        }

        private static void flushStore(IPreferenceStore store)
        {
            if (!(store instanceof IPersistentPreferenceStore persistent))
                return;
            try
            {
                persistent.save();
            }
            catch (IOException ex)
            {
                throw new RuntimeException("Не удалось записать PreferenceStore", ex); //$NON-NLS-1$
            }
        }

        private static IPreferenceStore preferenceStore()
        {
            Activator activator = Activator.getDefault();
            return activator != null ? activator.getPreferenceStore() : null;
        }

        static final class PresetSnapshot
        {
            final List<String> paths;
            final boolean blacklist;
            final boolean includeSub;
            final boolean includeParent;

            PresetSnapshot(List<String> paths, boolean blacklist,
                    boolean includeSub, boolean includeParent)
            {
                this.paths = paths != null ? List.copyOf(paths) : List.of();
                this.blacklist = blacklist;
                this.includeSub = includeSub;
                this.includeParent = includeParent;
            }
        }
    }

    /** Выбор имени пресета с возможностью удаления. */
    private static final class LoadPresetDialog extends Dialog
    {
        private static final int DELETE_ID = IDialogConstants.CLIENT_ID + 1;

        private final List<String> names;
        private org.eclipse.swt.widgets.List list;
        private String selectedName;

        LoadPresetDialog(Shell parentShell, List<String> names)
        {
            super(parentShell);
            this.names = new ArrayList<>(names);
        }

        String getSelectedName()
        {
            return selectedName;
        }

        @Override
        protected void configureShell(Shell newShell)
        {
            super.configureShell(newShell);
            newShell.setText(PRESET_DIALOG_TITLE);
        }

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite area = (Composite) super.createDialogArea(parent);
            Label hint = new Label(area, SWT.WRAP);
            hint.setText("Выберите сохранённое состояние фильтра:"); //$NON-NLS-1$
            hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            list = new org.eclipse.swt.widgets.List(area, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
            GridData listData = new GridData(SWT.FILL, SWT.FILL, true, true);
            listData.widthHint = 360;
            listData.heightHint = 220;
            list.setLayoutData(listData);
            for (String name : names)
                list.add(name);
            if (!names.isEmpty())
                list.select(0);
            list.addSelectionListener(new SelectionAdapter()
            {
                @Override public void widgetDefaultSelected(SelectionEvent e)
                {
                    okPressed();
                }
            });
            // GlobalListCopyHook ловит только notHandled; в модальном Dialog edit.copy
            // часто «обрабатывается» впустую — нужен явный postExecute* через CopyCommandSupport.
            CopyCommandSupport.wireCopyOverride(list, () -> copyListSelection(list));
            return area;
        }

        private static void copyListSelection(org.eclipse.swt.widgets.List list)
        {
            if (list == null || list.isDisposed())
                return;
            String[] selection = list.getSelection();
            if (selection.length == 0)
                return;
            String text = String.join("\n", selection); //$NON-NLS-1$
            Clipboard clipboard = new Clipboard(list.getDisplay());
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
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, DELETE_ID, "Удалить", false); //$NON-NLS-1$
            createButton(parent, IDialogConstants.OK_ID, "Загрузить", true); //$NON-NLS-1$
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        }

        @Override
        protected void buttonPressed(int buttonId)
        {
            if (buttonId == DELETE_ID)
            {
                deleteSelected();
                return;
            }
            super.buttonPressed(buttonId);
        }

        @Override
        protected void okPressed()
        {
            int index = list.getSelectionIndex();
            if (index < 0)
                return;
            selectedName = list.getItem(index);
            super.okPressed();
        }

        private void deleteSelected()
        {
            int index = list.getSelectionIndex();
            if (index < 0)
                return;
            String name = list.getItem(index);
            if (!MessageDialog.openQuestion(getShell(), PRESET_DIALOG_TITLE,
                    "Удалить состояние «" + name + "»?")) //$NON-NLS-1$ //$NON-NLS-2$
                return;
            FilterPresetStore.delete(name);
            list.remove(index);
            if (list.getItemCount() == 0)
            {
                close();
                return;
            }
            list.select(Math.min(index, list.getItemCount() - 1));
        }
    }
}


