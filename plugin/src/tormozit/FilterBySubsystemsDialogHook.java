package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.IDialogSettings;
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
import org.eclipse.swt.widgets.TypedListener;
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

    private static final String SMART_FILTER_KEY = "tormozit.filterBySubsystemsSmartFilter"; //$NON-NLS-1$
    private static final String HIGHLIGHT_KEY = "tormozit.filterBySubsystemsHighlight"; //$NON-NLS-1$
    private static final String LAST_PATTERN_KEY = "tormozit.filterBySubsystemsLastPattern"; //$NON-NLS-1$
    private static final String DESELECT_ALWAYS_KEY = "tormozit.filterBySubsystemsDeselectAlways"; //$NON-NLS-1$
    private static final String LAST_CHILD_CHECK_KEY = "tormozit.filterBySubsystemsLastChildCheck"; //$NON-NLS-1$
    private static final String TREE_CONTEXT_MENU_KEY = "tormozit.filterBySubsystemsTreeContextMenu"; //$NON-NLS-1$
    private static final String SIDE_MEMORY_KEY = "tormozit.filterBySubsystemsSideMemory"; //$NON-NLS-1$
    private static final String CTRL_MARK_KEY = "tormozit.filterBySubsystemsCtrlMark"; //$NON-NLS-1$
    private static final String GRAY_MARK_HINT_KEY = "tormozit.filterBySubsystemsGrayMarkHint"; //$NON-NLS-1$
    private static final String GRAY_MARK_HINT = "CTRL+клик превратит соседние динамические пометки в статические"; //$NON-NLS-1$
    private static final String COPY_HOOKED_KEY = "tormozit.filterBySubsystemsCopyHooked"; //$NON-NLS-1$
    private static final String COPY_ACTIVE_COLUMN_KEY = "tormozit.filterBySubsystemsCopyColumn"; //$NON-NLS-1$

    /**
     * Выбранная сторона подсистем в диалоге сравнения — на жизнь
     * {@code ViewerFilterBySubsystemsSettings} / batch редактора сравнения.
     */
    private static final Map<Object, ComparisonSide> SIDE_BY_COMPARISON_SESSION =
        Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Режим «чёрный список» (скрывать объекты выбранных подсистем вместо показа только их) —
     * на жизнь {@code ViewerFilterBySubsystemsSettings} редактора сравнения (тот же объект,
     * что {@code namedFilter.filterBySubsystemSettings} в {@code CompareConfigMenuHook}).
     */
    private static final Map<Object, Boolean> BLACKLIST_BY_SUBSYSTEMS_SETTINGS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private static final String BLACKLIST_CHECKBOX_KEY = "tormozit.filterBySubsystemsBlacklistCheckbox"; //$NON-NLS-1$
    private static final String BLACKLIST_CHECKBOX_LABEL =
        "Скрывать объекты выбранных подсистем (чёрный список)"; //$NON-NLS-1$

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

        installComfortToolbarActions(panel, viewer);
        installTreeContextMarkMenu(panel, viewer);
        installLastChildCheckGuard(panel, viewer);
        installTreeCellCopy(viewer);
        installDeselectAllAlwaysEnabled(dialog, panel, viewer);
        installComparisonSideMemory(shell, dialog, panel);
        installGrayMarkHint(panel);
        installBlacklistCheckbox(dialog, panel);
        installStandardCheckboxLabels(shell, panel);
        expandNavigatorProject(viewer, attempt);
        return installSmartFilter(panel, viewer);
    }

    /**
     * EDT всегда открывает диалог сравнения с {@code side = OTHER}. Запоминаем выбор
     * MAIN/OTHER по ключу настроек фильтра редактора и восстанавливаем при повторном открытии
     * в той же сессии сравнения.
     */
    private static void installComparisonSideMemory(Shell shell, Object dialog, Object panel)
    {
        if (shell == null || shell.isDisposed() || dialog == null || panel == null)
            return;
        if (Boolean.TRUE.equals(shell.getData(SIDE_MEMORY_KEY)))
            return;

        Object toolItemObj = Global.getField(dialog, "toolBarChangeSideElement"); //$NON-NLS-1$
        if (!(toolItemObj instanceof ToolItem) || ((ToolItem) toolItemObj).isDisposed())
            return;

        Object sessionKey = resolveComparisonSessionKey(dialog);
        if (sessionKey == null)
            return;

        shell.setData(SIDE_MEMORY_KEY, Boolean.TRUE);

        ComparisonSide remembered = SIDE_BY_COMPARISON_SESSION.get(sessionKey);
        Object currentObj = Global.getField(dialog, "side"); //$NON-NLS-1$
        ComparisonSide current = currentObj instanceof ComparisonSide side ? side : null;
        if (remembered != null && current != null && remembered != current)
        {
            Global.setField(dialog, "side", remembered); //$NON-NLS-1$
            Global.invokeVoid(panel, "updateChangeSideElement"); //$NON-NLS-1$
            syncChangeSideMenuSelection(dialog, remembered);
            FilterBySubsystemsDialogDebug.log("sideMemory: restored " + remembered); //$NON-NLS-1$
        }

        shell.addDisposeListener(e ->
        {
            Object sideObj = Global.getField(dialog, "side"); //$NON-NLS-1$
            if (!(sideObj instanceof ComparisonSide side))
                return;
            SIDE_BY_COMPARISON_SESSION.put(sessionKey, side);
            FilterBySubsystemsDialogDebug.step("sideMemory", "saved " + side); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    private static Object resolveComparisonSessionKey(Object dialog)
    {
        Object filterSettings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        if (filterSettings != null)
            return filterSettings;
        return Global.getField(dialog, "compareMergeProcessBatch"); //$NON-NLS-1$
    }

    /**
     * Пункты DROP_DOWN: 0 — «главного» ({@link ComparisonSide#MAIN}), 1 — «второго»
     * ({@link ComparisonSide#OTHER}); см. {@code createChangeSideAction} EDT.
     */
    private static void syncChangeSideMenuSelection(Object dialog, ComparisonSide side)
    {
        Menu menu = resolveChangeSideMenu(dialog);
        if (menu == null || menu.isDisposed() || menu.getItemCount() < 2)
            return;
        boolean main = side == ComparisonSide.MAIN;
        menu.getItem(0).setSelection(main);
        menu.getItem(1).setSelection(!main);
    }

    private static Menu resolveChangeSideMenu(Object dialog)
    {
        Object toolItemObj = Global.getField(dialog, "toolBarChangeSideElement"); //$NON-NLS-1$
        if (!(toolItemObj instanceof ToolItem toolItem) || toolItem.isDisposed())
            return null;
        for (Listener listener : toolItem.getListeners(SWT.Selection))
        {
            Object eventListener = listener instanceof TypedListener typed
                ? typed.getEventListener()
                : listener;
            if (eventListener == null)
                continue;
            if (!eventListener.getClass().getName().contains("DropdownSelectionListener")) //$NON-NLS-1$
                continue;
            Object menuObj = Global.getField(eventListener, "menu"); //$NON-NLS-1$
            if (menuObj instanceof Menu menu)
                return menu;
        }
        return null;
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

    /** Режим «чёрный список» включён для данной сессии сравнения (см. {@link #BLACKLIST_BY_SUBSYSTEMS_SETTINGS}). */
    static boolean isBlacklistMode(Object subsystemsSettings)
    {
        if (subsystemsSettings == null)
            return false;
        return Boolean.TRUE.equals(BLACKLIST_BY_SUBSYSTEMS_SETTINGS.get(subsystemsSettings));
    }

    /**
     * Чекбокс «чёрный список» — только для диалога сравнения ({@code filterSettings} есть только
     * у {@code com._1c.g5.v8.dt.internal.compare.ui.dialogs.FilterBySubsystemsDialog}). Ключ хранения
     * состояния — тот же {@code ViewerFilterBySubsystemsSettings}, которым в
     * {@link CompareConfigMenuHook} владеет {@code namedFilter.filterBySubsystemSettings} —
     * это позволяет {@code CorrectionViewerFilter} прочитать актуальный режим без прямой связи с диалогом.
     */
    private static void installBlacklistCheckbox(Object dialog, Object panel)
    {
        if (!(panel instanceof Composite panelComposite) || panelComposite.isDisposed())
            return;
        if (Boolean.TRUE.equals(panelComposite.getData(BLACKLIST_CHECKBOX_KEY)))
            return;
        Object settings = Global.getField(dialog, "filterSettings"); //$NON-NLS-1$
        if (settings == null)
            return;
        panelComposite.setData(BLACKLIST_CHECKBOX_KEY, Boolean.TRUE);

        Composite host = panelComposite.getParent();
        if (host == null || host.isDisposed() || !(host.getLayout() instanceof GridLayout))
            host = panelComposite;

        Button checkbox = new Button(host, SWT.CHECK);
        checkbox.setText(BLACKLIST_CHECKBOX_LABEL);
        checkbox.setToolTipText(
            "Инвертирует отбор: объекты выбранных подсистем скрываются в дереве сравнения, остальные показываются" //$NON-NLS-1$
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
                BLACKLIST_BY_SUBSYSTEMS_SETTINGS.put(settings, checkbox.getSelection());
                FilterBySubsystemsDialogDebug.step("blacklist", "checked=" + checkbox.getSelection()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
        host.layout(true, true);
        FilterBySubsystemsDialogDebug.log("blacklist: чекбокс добавлен"); //$NON-NLS-1$
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

    private static void installComfortToolbarActions(Object panel, CheckboxTreeViewer viewer)
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
            "Пометка выделенной подсистемы вместе с подчинёнными" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        comfortItem.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e)
            {
                showComfortMarkMenu(comfortItem, toolbar, panel, viewer);
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
            ToolItem item, ToolBar toolbar, Object panel, CheckboxTreeViewer viewer)
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
}


