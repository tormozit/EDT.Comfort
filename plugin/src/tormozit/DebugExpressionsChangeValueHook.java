package tormozit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * После «Изменить значение» EDT может вызвать {@code VariablesView.propertyChange → viewer.refresh()}.
 * На время сессии: {@code setModelDeltaMask(0)} + снятие {@code propertyChange}.
 * Пока сессия активна — без {@code treeViewer.update} (ломает lazy-дерево); одно обновление
 * целевой строки после закрытия диалога/редактора, с предварительной очисткой очереди delta.
 *
 * <p>Обёртка content provider ломает lazy-дерево — не используется.
 *
 * <p>Тулбар «Выражений»: «Пересчитать все» — штатный полный {@code viewer.refresh()}.
 *
 * <p>Inline-редактирование «Значение»: сессия при открытии cell editor (до commit).
 *
 * <p>Только при {@link ComfortSettings#isImproveDebuggerWindowsEnabled()}.
 */
public final class DebugExpressionsChangeValueHook implements IStartup
{
    private static final String EXPRESSIONS_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$
    private static final String HOOK_VIEWER_KEY = "tormozit.expressionsChangeValueHook"; //$NON-NLS-1$
    private static final String INLINE_CELL_WATCH_KEY = "tormozit.expressionsInlineCellWatch"; //$NON-NLS-1$
    private static final String INLINE_SESSION_COOLDOWN_KEY = "tormozit.expressionsInlineCooldown"; //$NON-NLS-1$
    private static final String RECALCULATE_TOOLBAR_GROUP = "tormozit.recalculateAll"; //$NON-NLS-1$
    private static final String STOCK_CP_KEY = "tormozit.expressionsChangeValue.stockCp"; //$NON-NLS-1$
    private static final String EXPRESSIONS_STOCK_MASK_KEY = "tormozit.expressionsStockMask"; //$NON-NLS-1$
    private static final String EXPRESSIONS_GUARD_KEY = "tormozit.expressionsPermanentGuard"; //$NON-NLS-1$
    private static final String EXPRESSIONS_INLINE_BLOCK_KEY = "tormozit.expressionsInlineBlockUntil"; //$NON-NLS-1$
    private static final String CHANGED_DEBUG_ELEMENT = "org.eclipse.debug.ui.changedDebugElement"; //$NON-NLS-1$
    private static final String COL_VAR_VALUE =
        "org.eclipse.debug.ui.VARIALBE_COLUMN_PRESENTATION.COL_VAR_VALUE"; //$NON-NLS-1$
    private static final int SESSION_TIMEOUT_MS = 120_000;
    private static final int POST_SESSION_SETTLE_MS = 2_000;
    private static final int POST_CELL_EDITOR_SETTLE_MS = 150;
    private static final int CONTEXT_MENU_CANCEL_FINISH_MS = 450;
    private static final int INLINE_SESSION_COOLDOWN_MS = 1500;
    private static final int INLINE_SETTLE_AFTER_CLOSE_MS = 1500;
    private static final int CELL_EDITOR_POLL_MS = 50;
    private static final int DIALOG_WATCH_MAX_ATTEMPTS = 600;
    private static final int DEBUG_EVENT_DETAIL_CONTENT = 512;
    private static final int SUPPRESS_MODEL_DELTA_MASK = 0;
    private static final int DEFAULT_MODEL_DELTA_MASK = -1;
    private static final int SELECTION_RESTORE_MAX_ATTEMPTS = 60;
    private static final int SELECTION_RESTORE_DELAY_MS = 100;
    private static final int DEFERRED_VALUE_COLUMN_MAX_ATTEMPTS = 25;
    private static final int DEFERRED_VALUE_COLUMN_DELAY_MS = 100;
    private static final Path AGENT_LOG_PATH = Path.of("C:\\VC\\EDT.Comfort\\debug-a38e3f.log"); //$NON-NLS-1$

    private static final Map<AbstractDebugView, Boolean> recalculateToolbarInstalled =
        Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean globalHooksInstalled;
    private static volatile boolean partListenersInstalled;
    private static volatile Listener changeValueSelectionFilter;
    private static volatile IDebugEventSetListener debugEventListener;
    private static volatile IPropertyChangeListener preferenceListener;

    private static final Map<Viewer, ChangeValueSession> pendingSessions =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Viewer, Boolean> inlineCellEditorWasActive =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(0));
    }

    private static void install(int attempt)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        installGlobalHooks();
        installPartListeners();
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        if (attempt < 48)
        {
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.timerExec(attempt < 8 ? 100 : 250, () -> install(attempt + 1));
        }
    }

    private static void installGlobalHooks()
    {
        if (globalHooksInstalled)
            return;
        globalHooksInstalled = true;

        if (changeValueSelectionFilter == null)
        {
            changeValueSelectionFilter = DebugExpressionsChangeValueHook::onChangeValueSelectionFilter;
            Display.getDefault().addFilter(SWT.Selection, changeValueSelectionFilter);
        }

        if (debugEventListener == null)
        {
            debugEventListener = DebugExpressionsChangeValueHook::handleDebugEvents;
            DebugPlugin.getDefault().addDebugEventListener(debugEventListener);
        }

        if (preferenceListener == null)
        {
            preferenceListener = DebugExpressionsChangeValueHook::handlePreferenceChange;
            IPreferenceStore store = preferenceStore();
            if (store != null)
                store.addPropertyChangeListener(preferenceListener);
        }

        DebugExpressionsDebug.step("install", "global hooks"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void installPartListeners()
    {
        if (partListenersInstalled)
            return;
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        partListenersInstalled = true;
        workbench.addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window) { }

            @Override
            public void windowActivated(IWorkbenchWindow window) { }

            @Override
            public void windowDeactivated(IWorkbenchWindow window) { }
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                tryHookDebugView(ref.getPart(false));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                tryHookDebugView(ref.getPart(false));
            }
        });
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IViewReference ref : page.getViewReferences())
            {
                if (ref != null)
                    tryHookDebugView(ref.getPart(false));
            }
        }
    }

    private static void tryHookDebugView(IWorkbenchPart part)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        if (!(part instanceof AbstractDebugView debugView))
            return;
        if (!isTargetView(debugView))
            return;
        if (EXPRESSIONS_VIEW_ID.equals(viewId(debugView)))
            installRecalculateAllToolbar(debugView);
        Viewer viewer = debugView.getViewer();
        if (viewer == null)
            return;
        boolean cpRestored = restoreStockContentProviderIfWrapped(viewer);
        if (Boolean.TRUE.equals(viewer.getData(HOOK_VIEWER_KEY)))
            return;
        viewer.setData(HOOK_VIEWER_KEY, Boolean.TRUE);
        if (isExpressionsView(debugView))
            installExpressionsPermanentGuard(debugView, viewer);
        scheduleInlineCellEditWatch(debugView, viewer);
        // #region agent log
        agentLog("E", "tryHookDebugView", "hook-installed", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "viewId", viewId(debugView), "cpRestored", String.valueOf(cpRestored)); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        DebugExpressionsDebug.step("hook", viewId(debugView)); //$NON-NLS-1$
    }

    /**
     * Сразу при открытии «Выражений»: снять {@code propertyChange → refresh()}.
     * {@code mask=0} — только на время сессии change-value, не постоянно (иначе блокируются evaluate/delta).
     */
    private static void installExpressionsPermanentGuard(AbstractDebugView debugView, Viewer viewer)
    {
        if (debugView == null || viewer == null || !isExpressionsView(debugView))
            return;
        if (Boolean.TRUE.equals(viewer.getData(EXPRESSIONS_GUARD_KEY)))
            return;
        viewer.setData(EXPRESSIONS_GUARD_KEY, Boolean.TRUE);
        boolean listenerRemoved = suppressStockRefresh(debugView);
        Object contentProvider = stockTreeModelContentProvider(viewer);
        int stockMask = DEFAULT_MODEL_DELTA_MASK;
        if (contentProvider != null)
        {
            stockMask = modelDeltaMask(contentProvider);
            viewer.setData(EXPRESSIONS_STOCK_MASK_KEY, stockMask);
        }
        // #region agent log
        agentLog("P", "installExpressionsPermanentGuard", "guard-active", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "listenerRemoved", String.valueOf(listenerRemoved), //$NON-NLS-1$
            "stockMask", String.valueOf(stockMask), "maskKeptStock", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        DebugExpressionsDebug.step("expressions", "permanent guard"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void installRecalculateAllToolbar(AbstractDebugView debugView)
    {
        if (debugView == null || Boolean.TRUE.equals(recalculateToolbarInstalled.get(debugView)))
            return;
        IViewSite site = debugView.getViewSite();
        if (site == null)
            return;
        IActionBars bars = site.getActionBars();
        if (bars == null)
            return;
        IToolBarManager toolbar = bars.getToolBarManager();
        if (toolbar == null)
            return;
        recalculateToolbarInstalled.put(debugView, Boolean.TRUE);
        toolbar.add(new Separator(RECALCULATE_TOOLBAR_GROUP));
        toolbar.add(new ContributionItem()
        {
            @Override
            public void fill(org.eclipse.swt.widgets.ToolBar bar, int index)
            {
                if (bar == null || bar.isDisposed())
                    return;
                ToolItem item = index >= 0 ? new ToolItem(bar, SWT.PUSH, index) : new ToolItem(bar, SWT.PUSH);
                item.setText("Пересчитать все"); //$NON-NLS-1$
                item.setToolTipText("Полное обновление всех выражений" + Global.pluginSignForTooltip()); //$NON-NLS-1$
                Image image = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ELCL_SYNCED);
                if (image != null)
                    item.setImage(image);
                item.addListener(SWT.Selection, e -> refreshAllExpressions(debugView));
            }
        });
        bars.updateActionBars();
        DebugExpressionsDebug.step("toolbar", "recalculate-all"); //$NON-NLS-1$ //$NON-NLS-2$
        // #region agent log
        agentLog("J", "installRecalculateAllToolbar", "installed", "post-fix"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
    }

    /** Штатный полный refresh панели «Выражения» (восстановление дерева после частичного update). */
    static void refreshAllExpressions(AbstractDebugView debugView)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled() || debugView == null)
            return;
        Viewer viewer = debugView.getViewer();
        if (viewer == null)
            return;
        runOnViewerDisplay(viewer, () ->
        {
            abortPendingSessionForViewer(viewer, "recalculate-all"); //$NON-NLS-1$
            restoreExpressionsStockModelDeltaMask(viewer);
            int evaluated = reevaluateAllWatchExpressions(debugView);
            AbstractTreeViewer treeViewer = asTreeViewer(viewer);
            TreeStateSnapshot treeState = TreeStateSnapshot.capture(treeViewer);
            if (treeViewer != null)
                treeViewer.refresh();
            refreshExpressionsDetailPane(debugView);
            Control refreshControl = treeViewer != null ? treeViewer.getControl() : null;
            Display refreshDisplay = refreshControl != null ? refreshControl.getDisplay() : null;
            if (refreshDisplay != null && !refreshDisplay.isDisposed() && treeState != null)
            {
                refreshDisplay.timerExec(SELECTION_RESTORE_DELAY_MS, () ->
                    scheduleTreeStateRestore(treeViewer, treeState));
            }
            // #region agent log
            agentLog("J", "refreshAllExpressions", "refreshed", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "viewId", viewId(debugView), //$NON-NLS-1$
                "hadSelection", String.valueOf(treeState != null), //$NON-NLS-1$
                "evaluated", String.valueOf(evaluated), //$NON-NLS-1$
                "maskKeptStock", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            DebugExpressionsDebug.step("recalculateAll", viewId(debugView) + " evaluated=" + evaluated); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    /** Штатный {@code IWatchExpression.evaluate()} для всех выражений панели. */
    private static int reevaluateAllWatchExpressions(AbstractDebugView debugView)
    {
        if (DebugPlugin.getDefault() == null)
            return 0;
        var manager = DebugPlugin.getDefault().getExpressionManager();
        if (manager == null)
            return 0;
        IExpression[] expressions = manager.getExpressions();
        if (expressions == null || expressions.length == 0)
            return 0;
        IDebugElement context = resolveExpressionEvaluationContext();
        int evaluated = 0;
        for (IExpression expression : expressions)
        {
            if (!(expression instanceof IWatchExpression watch))
                continue;
            try
            {
                if (context != null)
                    watch.setExpressionContext(context);
                watch.evaluate();
                evaluated++;
            }
            catch (Exception e)
            {
                DebugExpressionsDebug.problem("evaluate " + watch + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        // #region agent log
        agentLog("Q", "reevaluateAllWatchExpressions", "evaluated", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "count", String.valueOf(evaluated), //$NON-NLS-1$
            "context", context != null ? context.getClass().getSimpleName() : "null"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        return evaluated;
    }

    private static IDebugElement resolveExpressionEvaluationContext()
    {
        IAdaptable adaptable = DebugUITools.getDebugContext();
        if (adaptable instanceof IDebugElement element)
            return element;
        if (adaptable != null)
        {
            ILaunch launch = adaptable.getAdapter(ILaunch.class);
            if (launch != null)
            {
                try
                {
                    return launch.getDebugTarget();
                }
                catch (Exception ignored)
                {
                    // optional
                }
            }
        }
        return null;
    }

    private static void abortPendingSessionForViewer(Viewer viewer, String reason)
    {
        ChangeValueSession session = pendingSessions.get(viewer);
        if (session == null || session.completed)
            return;
        discardPendingModelDeltas(session.contentProvider);
        restoreModelDeltaMask(session, true);
        completeSession(session, reason);
    }

    /** Штатная mask (-1) перед полным refresh «Выражений». */
    private static void restoreExpressionsStockModelDeltaMask(Viewer viewer)
    {
        if (viewer == null)
            return;
        Object contentProvider = stockTreeModelContentProvider(viewer);
        if (contentProvider == null)
            return;
        Object stockObj = viewer.getData(EXPRESSIONS_STOCK_MASK_KEY);
        int stockMask = stockObj instanceof Integer mask ? mask.intValue() : DEFAULT_MODEL_DELTA_MASK;
        setModelDeltaMask(contentProvider, stockMask);
        // #region agent log
        agentLog("N", "restoreExpressionsStockModelDeltaMask", "mask-restored", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "mask", String.valueOf(stockMask)); //$NON-NLS-1$
        // #endregion
    }

    private static void scheduleTreeStateRestore(AbstractTreeViewer treeViewer, TreeStateSnapshot snapshot)
    {
        scheduleTreeStateRestore(treeViewer, snapshot, null);
    }

    private static void scheduleTreeStateRestore(AbstractTreeViewer treeViewer, TreeStateSnapshot snapshot,
        Runnable onComplete)
    {
        if (treeViewer == null || snapshot == null)
        {
            if (onComplete != null)
                onComplete.run();
            return;
        }
        Control control = treeViewer.getControl();
        if (control == null || control.isDisposed())
        {
            if (onComplete != null)
                onComplete.run();
            return;
        }
        Display display = control.getDisplay();
        if (display == null || display.isDisposed())
        {
            if (onComplete != null)
                onComplete.run();
            return;
        }
        SelectionRestoreGuard guard = new SelectionRestoreGuard(snapshot);
        guard.attach(treeViewer);
        final int[] attempt = { 0 };
        Runnable finish = () ->
        {
            guard.detach(treeViewer);
            if (onComplete != null)
                onComplete.run();
        };
        Runnable task = new Runnable()
        {
            @Override
            public void run()
            {
                if (control.isDisposed())
                {
                    finish.run();
                    return;
                }
                if (guard.isUserOverride())
                {
                    // #region agent log
                    agentLog("S", "scheduleTreeStateRestore", "user-kept-selection", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "attempt", String.valueOf(attempt[0]), //$NON-NLS-1$
                        "selected", describe(selectedVariable(treeViewer))); //$NON-NLS-1$
                    // #endregion
                    finish.run();
                    return;
                }
                snapshot.materializeExpandedPaths(treeViewer);
                TreeStateSnapshot.MaterializeState state = snapshot.selectionMaterializeState(treeViewer);
                int rootCount = treeRootItemCount(treeViewer);
                // #region agent log
                agentLog("T", "scheduleTreeStateRestore", "attempt-state", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "attempt", String.valueOf(attempt[0]), //$NON-NLS-1$
                    "materialize", state.name(), "rootCount", String.valueOf(rootCount), //$NON-NLS-1$ //$NON-NLS-2$
                    "selectionPath", snapshot.selectionPathText()); //$NON-NLS-1$
                // #endregion
                if (state == TreeStateSnapshot.MaterializeState.WAITING_CHILDREN)
                {
                    snapshot.nudgeSelectionPathChildren(treeViewer);
                    // #region agent log
                    agentLog("S", "scheduleTreeStateRestore", "waiting-children", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "attempt", String.valueOf(attempt[0]), //$NON-NLS-1$
                        "selectionPath", snapshot.selectionPathText()); //$NON-NLS-1$
                    // #endregion
                    attempt[0]++;
                    if (attempt[0] < SELECTION_RESTORE_MAX_ATTEMPTS)
                        display.timerExec(SELECTION_RESTORE_DELAY_MS, this);
                    else
                        finish.run();
                    return;
                }
                if (state == TreeStateSnapshot.MaterializeState.MISSING_PREFIX)
                {
                    attempt[0]++;
                    if (attempt[0] < SELECTION_RESTORE_MAX_ATTEMPTS)
                        display.timerExec(SELECTION_RESTORE_DELAY_MS, this);
                    else
                    {
                        // #region agent log
                        agentLog("K", "scheduleTreeStateRestore", "tree-state-restore-failed", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            "reason", "missing-prefix", "depth", String.valueOf(snapshot.pathDepth())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        // #endregion
                        finish.run();
                    }
                    return;
                }
                IBslVariable selected = selectedVariable(treeViewer);
                if (!snapshot.matchesSelectionPin(selected))
                    snapshot.applySelection(treeViewer, guard);
                selected = selectedVariable(treeViewer);
                boolean pinOk = snapshot.matchesSelectionPin(selected);
                boolean rowReady = snapshot.isTargetRowReady(treeViewer);
                // #region agent log
                agentLog("K", "scheduleTreeStateRestore", pinOk && rowReady ? "tree-state-restored" : "tree-state-pending", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "attempt", String.valueOf(attempt[0]), //$NON-NLS-1$
                    "depth", String.valueOf(snapshot.pathDepth()), //$NON-NLS-1$
                    "expanded", String.valueOf(snapshot.expandedPathCount()), //$NON-NLS-1$
                    "selected", describe(selected), //$NON-NLS-1$
                    "pinOk", String.valueOf(pinOk), "rowReady", String.valueOf(rowReady), //$NON-NLS-1$ //$NON-NLS-2$
                    "rootCount", String.valueOf(rootCount)); //$NON-NLS-1$
                // #endregion
                if (pinOk && rowReady)
                {
                    finish.run();
                    return;
                }
                attempt[0]++;
                if (attempt[0] < SELECTION_RESTORE_MAX_ATTEMPTS)
                    display.timerExec(SELECTION_RESTORE_DELAY_MS, this);
                else
                    finish.run();
            }
        };
        display.asyncExec(task);
    }

    /**
     * Пока ждём lazy-детей — не перебиваем выбор, если пользователь сам выбрал другую строку
     * (не предок целевого watch-пути).
     */
    private static final class SelectionRestoreGuard
    {
        private final TreeStateSnapshot snapshot;
        private AbstractTreeViewer treeViewer;
        private volatile boolean userOverride;
        private int suppressEvents;
        private final ISelectionChangedListener listener;

        SelectionRestoreGuard(TreeStateSnapshot snapshot)
        {
            this.snapshot = snapshot;
            this.listener = event -> onSelectionChanged();
        }

        void attach(AbstractTreeViewer viewer)
        {
            treeViewer = viewer;
            if (viewer != null)
                viewer.addSelectionChangedListener(listener);
        }

        void detach(AbstractTreeViewer viewer)
        {
            if (viewer != null)
                viewer.removeSelectionChangedListener(listener);
            treeViewer = null;
        }

        boolean isUserOverride()
        {
            return userOverride;
        }

        void runProgrammatic(Runnable action)
        {
            suppressEvents++;
            try
            {
                action.run();
            }
            finally
            {
                suppressEvents--;
            }
        }

        private void onSelectionChanged()
        {
            if (suppressEvents > 0 || userOverride || snapshot == null || treeViewer == null)
                return;
            IBslVariable selected = selectedVariable(treeViewer);
            if (!snapshot.isUserIntentionalSelectionChange(selected))
                return;
            userOverride = true;
            // #region agent log
            agentLog("S", "SelectionRestoreGuard", "user-override", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "selected", describe(selected), //$NON-NLS-1$
                "selectedPath", selected != null ? BslInspectSupport.resolveVariableInspectExpression(selected) : "", //$NON-NLS-1$ //$NON-NLS-2$
                "targetPath", snapshot.selectionPinWatchPath()); //$NON-NLS-1$
            // #endregion
        }
    }

    private static boolean isExpressionsView(AbstractDebugView debugView)
    {
        return debugView != null && EXPRESSIONS_VIEW_ID.equals(viewId(debugView));
    }

    private static void refreshExpressionsDetailPane(AbstractDebugView debugView)
    {
        if (debugView == null)
            return;
        try
        {
            Global.invokeVoid(debugView, "refreshDetailPaneContents"); //$NON-NLS-1$
        }
        catch (RuntimeException ignored)
        {
            // optional — есть не у всех наследников VariablesView
        }
    }

    /** Восстанавливает штатный CP, если остался от отменённой обёртки proxy. */
    private static boolean restoreStockContentProviderIfWrapped(Viewer viewer)
    {
        if (!(viewer instanceof AbstractTreeViewer treeViewer))
            return false;
        Object stock = viewer.getData(STOCK_CP_KEY);
        if (stock instanceof IContentProvider stockCp)
        {
            treeViewer.setContentProvider(stockCp);
            viewer.setData(STOCK_CP_KEY, null);
            // #region agent log
            agentLog("A", "restoreStockContentProvider", "restored", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "stockClass", stockCp.getClass().getName()); //$NON-NLS-1$
            // #endregion
            return true;
        }
        IContentProvider current = treeViewer.getContentProvider();
        if (current != null && java.lang.reflect.Proxy.isProxyClass(current.getClass()))
        {
            // #region agent log
            agentLog("A", "restoreStockContentProvider", "proxy-without-stock", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "proxyClass", current.getClass().getName()); //$NON-NLS-1$
            // #endregion
        }
        return false;
    }

    // #region agent log
    private static void agentLog(String hypothesisId, String location, String message, String runId,
        String... kv)
    {
        try
        {
            StringBuilder data = new StringBuilder("{");
            if (kv != null)
            {
                for (int i = 0; i + 1 < kv.length; i += 2)
                {
                    if (i > 0)
                        data.append(',');
                    data.append('"').append(jsonEscape(kv[i])).append("\":\"").append(jsonEscape(kv[i + 1])).append('"');
                }
            }
            data.append('}');
            String line = String.format(
                "{\"sessionId\":\"a38e3f\",\"hypothesisId\":\"%s\",\"location\":\"%s\",\"message\":\"%s\",\"runId\":\"%s\",\"data\":%s,\"timestamp\":%d}%n", //$NON-NLS-1$
                jsonEscape(hypothesisId), jsonEscape(location), jsonEscape(message), jsonEscape(runId), data,
                System.currentTimeMillis());
            Files.writeString(AGENT_LOG_PATH, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        }
        catch (Exception ignored)
        {
            // agent log must not break debugger UI
        }
    }

    private static String jsonEscape(String value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        return value.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
    // #endregion

    private static void onChangeValueSelectionFilter(Event e)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        if (!(e.widget instanceof MenuItem item) || item.isDisposed())
            return;
        if (!isChangeValueMenuItem(item))
            return;

        Menu menu = item.getParent();
        if (menu == null || menu.isDisposed())
            return;
        Tree tree = treeForContextMenu(menu);
        if (tree == null || tree.isDisposed())
            return;

        AbstractDebugView debugView = debugViewForTree(tree);
        if (debugView == null || !isTargetView(debugView))
            return;

        Viewer viewer = debugView.getViewer();
        if (viewer == null)
            return;

        IBslVariable variable = selectedVariable(viewer);
        if (variable == null)
            return;

        beginChangeValueSession(debugView, viewer, variable, "context-menu"); //$NON-NLS-1$
    }

    private static void scheduleInlineCellEditWatch(AbstractDebugView debugView, Viewer viewer)
    {
        if (debugView == null || viewer == null)
            return;
        if (Boolean.TRUE.equals(viewer.getData(INLINE_CELL_WATCH_KEY)))
            return;
        viewer.setData(INLINE_CELL_WATCH_KEY, Boolean.TRUE);
        pollInlineCellEdit(debugView, viewer);
    }

    /** Сессия до commit: иначе {@code propertyChange → refresh()} успевает раньше подавления. */
    private static void pollInlineCellEdit(AbstractDebugView debugView, Viewer viewer)
    {
        if (viewer == null || debugView == null)
            return;
        Control control = viewer.getControl();
        if (control == null || control.isDisposed())
            return;
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;

        Boolean active = isCellEditorActive(viewer);
        boolean isActive = Boolean.TRUE.equals(active);
        boolean wasActive = Boolean.TRUE.equals(inlineCellEditorWasActive.get(viewer));
        Long blockUntil = (Long) viewer.getData(EXPRESSIONS_INLINE_BLOCK_KEY);
        if (blockUntil != null && System.currentTimeMillis() < blockUntil.longValue())
        {
            inlineCellEditorWasActive.put(viewer, isActive);
            Display blockDisplay = control.getDisplay();
            if (blockDisplay != null && !blockDisplay.isDisposed())
                blockDisplay.timerExec(CELL_EDITOR_POLL_MS, () -> pollInlineCellEdit(debugView, viewer));
            return;
        }
        if (isActive && !wasActive)
        {
            Long cooldownUntil = (Long) viewer.getData(INLINE_SESSION_COOLDOWN_KEY);
            if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil.longValue())
            {
                inlineCellEditorWasActive.put(viewer, isActive);
                Display cooldownDisplay = control.getDisplay();
                if (cooldownDisplay != null && !cooldownDisplay.isDisposed())
                    cooldownDisplay.timerExec(CELL_EDITOR_POLL_MS, () -> pollInlineCellEdit(debugView, viewer));
                return;
            }
            ChangeValueSession existing = pendingSessions.get(viewer);
            IBslVariable variable = selectedVariable(viewer);
            if (variable != null && (existing == null || existing.completed))
            {
                // #region agent log
                agentLog("L", "pollInlineCellEdit", "inline-edit-begin", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "variable", describe(variable), "viewId", viewId(debugView)); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                beginChangeValueSession(debugView, viewer, variable, "inline-cell"); //$NON-NLS-1$
            }
        }
        inlineCellEditorWasActive.put(viewer, isActive);

        Display display = control.getDisplay();
        if (display != null && !display.isDisposed())
            display.timerExec(CELL_EDITOR_POLL_MS, () -> pollInlineCellEdit(debugView, viewer));
    }

    private static void beginChangeValueSession(AbstractDebugView debugView, Viewer viewer,
        IBslVariable variable, String source)
    {
        ChangeValueSession existing = pendingSessions.get(viewer);
        if (existing != null && !existing.completed)
        {
            if (matchesTarget(existing, variable) && source.equals(existing.source))
                return;
            finishChangeValueSession(existing, "replace"); //$NON-NLS-1$
        }

        boolean expressionsGuard = isExpressionsView(debugView)
            && Boolean.TRUE.equals(viewer.getData(EXPRESSIONS_GUARD_KEY));
        if (!expressionsGuard)
        {
            if (!suppressStockRefresh(debugView))
            {
                DebugExpressionsDebug.problem("suppressStockRefresh failed"); //$NON-NLS-1$
                return;
            }
        }
        else
        {
            // #region agent log
            agentLog("P", "beginChangeValueSession", "guard-already-active", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "source", source); //$NON-NLS-1$
            // #endregion
        }

        Object contentProvider = stockTreeModelContentProvider(viewer);
        int previousModelDeltaMask = DEFAULT_MODEL_DELTA_MASK;
        if (contentProvider != null)
        {
            if (isExpressionsView(debugView))
            {
                Object stockObj = viewer.getData(EXPRESSIONS_STOCK_MASK_KEY);
                if (stockObj instanceof Integer stockMask)
                    previousModelDeltaMask = stockMask.intValue();
                else
                {
                    previousModelDeltaMask = modelDeltaMask(contentProvider);
                    viewer.setData(EXPRESSIONS_STOCK_MASK_KEY, previousModelDeltaMask);
                }
            }
            else
                previousModelDeltaMask = modelDeltaMask(contentProvider);
            setModelDeltaMask(contentProvider, SUPPRESS_MODEL_DELTA_MASK);
            // #region agent log
            agentLog("F", "suppressModelDelta", expressionsGuard ? "mask-set-session" : "mask-set", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "previousMask", String.valueOf(previousModelDeltaMask)); //$NON-NLS-1$
            // #endregion
        }

        ChangeValueSession session = new ChangeValueSession(debugView, viewer, variable,
            contentProvider, previousModelDeltaMask,
            TreeStateSnapshot.capture(asTreeViewer(viewer)), source);
        session.preEditValueColumnText = readValueColumnText(asTreeViewer(viewer), variable);
        pendingSessions.put(viewer, session);
        // #region agent log
        agentLog("C", "beginChangeValueSession", "session-begin", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "source", source, "variable", describe(variable), //$NON-NLS-1$ //$NON-NLS-2$
            "pendingCount", String.valueOf(pendingSessions.size()), //$NON-NLS-1$
            "selectionDepth", String.valueOf(session.treeStateSnapshot != null ? session.treeStateSnapshot.pathDepth() : 0), //$NON-NLS-1$
            "expanded", String.valueOf(session.treeStateSnapshot != null ? session.treeStateSnapshot.expandedPathCount() : 0)); //$NON-NLS-1$
        // #endregion
        DebugExpressionsDebug.step("changeValue", "begin " + describe(variable)); //$NON-NLS-1$ //$NON-NLS-2$

        if ("context-menu".equals(source)) //$NON-NLS-1$
            scheduleContextMenuDialogFinish(session);
        else
        {
            watchChangeValueDialog(session, 0);
            watchCellEditorClose(session, 0);
        }
        scheduleSessionTimeout(session);
    }

    private static boolean suppressStockRefresh(AbstractDebugView debugView)
    {
        IPreferenceStore store = preferenceStore();
        IPropertyChangeListener listener = asPropertyChangeListener(debugView);
        if (store == null || listener == null)
            return false;
        try
        {
            store.removePropertyChangeListener(listener);
            // #region agent log
            agentLog("D", "suppressStockRefresh", "listener-removed", "post-fix"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            return true;
        }
        catch (Exception e)
        {
            DebugExpressionsDebug.problem("removePropertyChangeListener: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static void restoreStockRefresh(AbstractDebugView debugView)
    {
        if (isExpressionsView(debugView))
        {
            // #region agent log
            agentLog("N", "restoreStockRefresh", "skipped-expressions", "post-fix"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            return;
        }
        IPropertyChangeListener listener = asPropertyChangeListener(debugView);
        if (listener == null)
            return;
        IPreferenceStore store = preferenceStore();
        if (store == null)
            return;
        try
        {
            store.addPropertyChangeListener(listener);
            // #region agent log
            agentLog("D", "restoreStockRefresh", "listener-restored", "post-fix"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
        }
        catch (Exception e)
        {
            DebugExpressionsDebug.problem("addPropertyChangeListener: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static IPropertyChangeListener asPropertyChangeListener(AbstractDebugView debugView)
    {
        if (debugView instanceof IPropertyChangeListener listener)
            return listener;
        if (debugView != null && debugView.getClass().getName().contains("VariablesView")) //$NON-NLS-1$
            return (IPropertyChangeListener) debugView;
        return null;
    }

    private static void handleDebugEvents(DebugEvent[] events)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled() || events == null)
            return;
        for (DebugEvent event : events)
        {
            if (event.getKind() != DebugEvent.CHANGE)
                continue;
            if (!(event.getSource() instanceof IBslVariable variable))
                continue;
            if ((event.getDetail() & DEBUG_EVENT_DETAIL_CONTENT) == 0)
                continue;
            notifyVariableChanged(variable, "debug-event"); //$NON-NLS-1$
        }
    }

    private static void notifyVariableChanged(IBslVariable variable, String reason)
    {
        synchronized (pendingSessions)
        {
            for (ChangeValueSession session : new ArrayList<>(pendingSessions.values()))
            {
                if (session.completed)
                    continue;
                if (!matchesTarget(session, variable))
                    continue;
                session.valueChanged = true;
                session.lastChangedVariable = variable;
                // #region agent log
                agentLog("G", "notifyVariableChanged", "value-changed-flag", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "reason", reason, "variable", describe(variable)); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                scheduleFinishChangeValueSession(session, reason);
            }
        }
    }

    private static void handlePreferenceChange(PropertyChangeEvent event)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled() || event == null)
            return;
        if (!CHANGED_DEBUG_ELEMENT.equals(event.getProperty()))
            return;
        // #region agent log
        agentLog("O", "handlePreferenceChange", "changedDebugElement-fired", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "pendingSessions", String.valueOf(pendingSessions.size())); //$NON-NLS-1$
        // #endregion
        synchronized (pendingSessions)
        {
            for (ChangeValueSession session : new ArrayList<>(pendingSessions.values()))
            {
                if (session.completed)
                    continue;
                IBslVariable variable = resolveChangedVariable(session, event);
                if (variable != null)
                    notifyVariableChanged(variable, "preference"); //$NON-NLS-1$
            }
        }
    }

    private static IBslVariable resolveChangedVariable(ChangeValueSession session, PropertyChangeEvent event)
    {
        Object newValue = event.getNewValue();
        if (newValue instanceof IBslVariable variable)
            return variable;
        Object oldValue = event.getOldValue();
        if (oldValue instanceof IBslVariable variable)
            return variable;
        return session.targetVariable;
    }

    private static boolean matchesTarget(ChangeValueSession session, IBslVariable variable)
    {
        if (session == null || variable == null || session.targetVariable == null)
            return false;
        if (session.targetVariable == variable)
            return true;
        return sameDebugVariable(session.targetVariable, variable);
    }

    private static void scheduleFinishChangeValueSession(ChangeValueSession session, String reason)
    {
        if (session == null || session.completed)
            return;
        if ("context-menu".equals(session.source) //$NON-NLS-1$
            && ("debug-event".equals(reason) || "preference".equals(reason)) //$NON-NLS-1$ //$NON-NLS-2$
            && session.awaitingModalDialog)
        {
            // #region agent log
            agentLog("V", "scheduleFinishChangeValueSession", "deferred-modal-await", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "reason", reason, "variable", describe(session.targetVariable)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        session.finishGeneration++;
        final int generation = session.finishGeneration;
        int settleMs = POST_SESSION_SETTLE_MS;
        if ("cell-editor-close".equals(reason) || "dialog-close".equals(reason)) //$NON-NLS-1$ //$NON-NLS-2$
            settleMs = POST_CELL_EDITOR_SETTLE_MS;
        else if ("inline-cell".equals(session.source) && session.cellEditorWasActive //$NON-NLS-1$
            && ("debug-event".equals(reason) || "preference".equals(reason))) //$NON-NLS-1$ //$NON-NLS-2$
            settleMs = POST_CELL_EDITOR_SETTLE_MS;
        else if ("context-menu".equals(session.source) && session.dialogClosed //$NON-NLS-1$
            && ("debug-event".equals(reason) || "preference".equals(reason))) //$NON-NLS-1$ //$NON-NLS-2$
            settleMs = POST_CELL_EDITOR_SETTLE_MS;
        // #region agent log
        agentLog("G", "scheduleFinishChangeValueSession", "finish-scheduled", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "reason", reason, "settleMs", String.valueOf(settleMs), //$NON-NLS-1$ //$NON-NLS-2$
            "generation", String.valueOf(generation)); //$NON-NLS-1$
        // #endregion
        Display display = displayOf(session.viewer);
        if (display == null || display.isDisposed())
            return;
        final int delayMs = settleMs;
        display.timerExec(delayMs, () -> runOnViewerDisplay(session.viewer, () ->
        {
            if (!session.completed && session.finishGeneration == generation)
                finishChangeValueSession(session, reason);
        }));
    }

    private static void finishChangeValueSession(ChangeValueSession session, String reason)
    {
        if (session == null || session.completed)
            return;
        discardPendingModelDeltas(session.contentProvider);
        // updateElement — только после восстановления дерева; затем повторный pin состояния.
        boolean applyUpdateAfterRestore = session.valueChanged
            || "cell-editor-close".equals(reason) //$NON-NLS-1$
            || "dialog-close".equals(reason); //$NON-NLS-1$
        // #region agent log
        agentLog("G", "finishChangeValueSession", "finish", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "reason", reason, "valueChanged", String.valueOf(session.valueChanged), //$NON-NLS-1$ //$NON-NLS-2$
            "applyUpdateAfterRestore", String.valueOf(applyUpdateAfterRestore)); //$NON-NLS-1$
        // #endregion
        completeSession(session, reason, false, false);
        if (session.viewer != null)
        {
            session.viewer.setData(INLINE_SESSION_COOLDOWN_KEY,
                Long.valueOf(System.currentTimeMillis() + INLINE_SESSION_COOLDOWN_MS));
            if (isExpressionsView(session.debugView))
            {
                session.viewer.setData(EXPRESSIONS_INLINE_BLOCK_KEY,
                    Long.valueOf(System.currentTimeMillis() + INLINE_SETTLE_AFTER_CLOSE_MS));
            }
        }
        AbstractTreeViewer treeViewer = asTreeViewer(session.viewer);
        TreeStateSnapshot treeState = session.treeStateSnapshot;
        scheduleTreeStateRestore(treeViewer, treeState, () ->
        {
            restoreStockRefreshAfterSelection(session, reason);
            if (isExpressionsView(session.debugView))
            {
                restoreExpressionsStockModelDeltaMask(session.viewer);
                // #region agent log
                agentLog("U", "finishChangeValueSession", "mask-restored-after-session", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "reason", reason); //$NON-NLS-1$
                // #endregion
            }
            if (!applyUpdateAfterRestore)
            {
                scheduleTreeStateRestore(treeViewer, treeState);
                return;
            }
            IBslVariable updateVariable = resolveUpdateVariable(session);
            IBslVariable liveSelection = selectedVariable(treeViewer);
            if (liveSelection != null)
                updateVariable = liveSelection;
            boolean columnUpdated = applyTargetedUpdate(session.viewer, session.contentProvider, updateVariable,
                reason + "-after-restore"); //$NON-NLS-1$
            boolean modelStillPreEdit = session.valueChanged
                && valueColumnMatchesPreEdit(updateVariable, session.preEditValueColumnText);
            if (!columnUpdated || modelStillPreEdit)
                applyVariableContentDelta(session.contentProvider, updateVariable, session.viewer);
            if (!columnUpdated || modelStillPreEdit)
                columnUpdated = applyTargetedUpdate(session.viewer, session.contentProvider, updateVariable,
                    reason + "-after-delta"); //$NON-NLS-1$
            if (modelStillPreEdit)
                scheduleDeferredExpressionValueRefresh(session, treeViewer, updateVariable, reason, 0);
            // #region agent log
            agentLog("I", "finishChangeValueSession", "column-update-after-restore", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "reason", reason, "columnUpdated", String.valueOf(columnUpdated), //$NON-NLS-1$ //$NON-NLS-2$
                "modelStillPreEdit", String.valueOf(modelStillPreEdit), //$NON-NLS-1$
                "variable", describe(updateVariable)); //$NON-NLS-1$
            // #endregion
            scheduleTreeStateRestore(treeViewer, treeState);
        });
    }

    private static void restoreStockRefreshAfterSelection(ChangeValueSession session, String reason)
    {
        if (session == null)
            return;
        AbstractTreeViewer treeViewer = asTreeViewer(session.viewer);
        TreeStateSnapshot snapshot = session.treeStateSnapshot;
        Display display = displayOf(session.viewer);

        if (isExpressionsView(session.debugView))
        {
            // #region agent log
            agentLog("N", "restoreStockRefreshAfterSelection", "expressions-done", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "reason", reason, "listenerRestored", "false"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }

        restoreModelDeltaMask(session);
        if (display != null && !display.isDisposed() && treeViewer != null && snapshot != null)
            display.asyncExec(() -> scheduleTreeStateRestore(treeViewer, snapshot));

        if (session.refreshSuppressed)
            restoreStockRefresh(session.debugView);
        // #region agent log
        agentLog("M", "restoreStockRefreshAfterSelection", "listener-restored", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "reason", reason, "maskRestored", String.valueOf(session.modelDeltaMaskRestored)); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion

        if (display != null && !display.isDisposed() && treeViewer != null && snapshot != null)
            display.asyncExec(() -> scheduleTreeStateRestore(treeViewer, snapshot));
    }

    private static IBslVariable resolveUpdateVariable(ChangeValueSession session)
    {
        if (session.lastChangedVariable != null)
            return session.lastChangedVariable;
        return session.targetVariable;
    }

    private static void discardPendingModelDeltas(Object contentProvider)
    {
        if (contentProvider == null)
            return;
        try
        {
            Object job = Global.getField(contentProvider, "fDelayedDoModelChangeJob"); //$NON-NLS-1$
            if (job == null)
                return;
            Global.invoke(job, "cancel"); //$NON-NLS-1$
            Object queueObj = Global.getField(job, "fQueue"); //$NON-NLS-1$
            int cleared = 0;
            if (queueObj instanceof List<?> queue)
            {
                synchronized (queue)
                {
                    cleared = queue.size();
                    queue.clear();
                }
            }
            // #region agent log
            agentLog("G", "discardPendingModelDeltas", "queue-cleared", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "cleared", String.valueOf(cleared)); //$NON-NLS-1$
            // #endregion
        }
        catch (Exception e)
        {
            DebugExpressionsDebug.problem("discardPendingModelDeltas: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static boolean applyTargetedUpdate(Viewer viewer, Object contentProvider, IBslVariable variable,
        String reason)
    {
        if (viewer == null || variable == null)
            return false;
        AbstractTreeViewer treeViewer = asTreeViewer(viewer);
        boolean painted = treeViewer != null && isExpressionsViewer(viewer)
            && paintExpressionsValueCell(treeViewer, variable, reason);
        if (contentProvider != null && refreshVariableColumnLabels(viewer, contentProvider, variable, reason))
            return true;
        if (painted)
            return true;
        if (treeViewer == null)
        {
            DebugExpressionsDebug.problem("update: no tree viewer"); //$NON-NLS-1$
            return false;
        }
        Object found = testFindItem(treeViewer, variable);
        // #region agent log
        agentLog("F", "applyTargetedUpdate", "tree-update-fallback", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "reason", reason, "variable", describe(variable), //$NON-NLS-1$ //$NON-NLS-2$
            "foundItem", String.valueOf(found != null)); //$NON-NLS-1$
        // #endregion
        if (found == null)
            return false;
        treeViewer.update(variable, null);
        return true;
    }

    /** Прямая перерисовка ячейки «Значение» — {@code updateElement} при mask=0 не всегда виден в UI. */
    private static boolean paintExpressionsValueCell(AbstractTreeViewer treeViewer, IBslVariable variable,
        String reason)
    {
        if (treeViewer == null || variable == null)
            return false;
        IBslVariable live = selectedVariable(treeViewer);
        if (live != null && sameDebugVariable(live, variable))
            variable = live;
        Object found = findTreeItemForVariable(treeViewer, variable);
        if (!(found instanceof TreeItem item) || item.isDisposed())
        {
            // #region agent log
            agentLog("P", "paintExpressionsValueCell", "no-item", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "reason", reason, "variable", describe(variable)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return false;
        }
        int[] columns = valueColumnIndices(treeViewer);
        if (columns.length == 0)
            columns = new int[] { 1 };
        String before = item.getText(columns[0]);
        String after = resolveValueColumnText(variable, before);
        for (int column : columns)
            item.setText(column, after != null ? after : ""); //$NON-NLS-1$
        Tree tree = item.getParent();
        if (tree != null && !tree.isDisposed())
            tree.redraw();
        // #region agent log
        agentLog("P", "paintExpressionsValueCell", "painted", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "reason", reason, "variable", describe(variable), //$NON-NLS-1$ //$NON-NLS-2$
            "before", before != null ? before : "", "after", after != null ? after : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // #endregion
        return true;
    }

    private static Object findTreeItemForVariable(AbstractTreeViewer treeViewer, IBslVariable variable)
    {
        if (treeViewer == null || variable == null)
            return null;
        Object found = testFindItem(treeViewer, variable);
        if (found != null)
            return found;
        Tree tree = treeOf(treeViewer);
        if (tree == null || tree.isDisposed())
            return null;
        return findTreeItemByVariableKey(tree.getItems(), variable);
    }

    private static TreeItem findTreeItemByVariableKey(TreeItem[] items, IBslVariable variable)
    {
        if (items == null || variable == null)
            return null;
        String key = TreeElementKey.forElement(variable);
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            Object data = item.getData();
            if (data instanceof IBslVariable candidate && sameDebugVariable(candidate, variable))
                return item;
            if (key.equals(TreeElementKey.forData(data)))
                return item;
            TreeItem nested = findTreeItemByVariableKey(item.getItems(), variable);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static boolean sameDebugVariable(IBslVariable a, IBslVariable b)
    {
        if (a == null || b == null)
            return false;
        if (a == b)
            return true;
        String pathA = BslInspectSupport.resolveVariableInspectExpression(a);
        String pathB = BslInspectSupport.resolveVariableInspectExpression(b);
        if (!pathA.isBlank() && pathA.equals(pathB))
            return true;
        return TreeElementKey.variableSegmentKey(a).equals(TreeElementKey.variableSegmentKey(b));
    }

    private static String resolveValueColumnText(IBslVariable variable, String fallback)
    {
        if (variable == null)
            return fallback;
        try
        {
            IBslValue bsl = variable.getValue();
            if (bsl == null)
                return fallback;
            String presentation = bsl.getValueString();
            if (presentation == null)
                return fallback;
            String formatted = DebugStringValueFormat.formatForStockTreeQuick(presentation, variable, bsl);
            return formatted != null ? formatted : fallback;
        }
        catch (Exception ignored)
        {
            return fallback;
        }
    }

    private static String readValueColumnText(AbstractTreeViewer treeViewer, IBslVariable variable)
    {
        if (treeViewer == null || variable == null)
            return null;
        Object found = findTreeItemForVariable(treeViewer, variable);
        if (!(found instanceof TreeItem item) || item.isDisposed())
            return null;
        int[] columns = valueColumnIndices(treeViewer);
        int column = columns.length > 0 ? columns[0] : 1;
        return item.getText(column);
    }

    private static String normalizeValueColumnDisplay(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
            return trimmed.substring(1, trimmed.length() - 1);
        return trimmed;
    }

    private static boolean valueColumnMatchesPreEdit(IBslVariable variable, String preEditText)
    {
        if (preEditText == null)
            return false;
        String current = resolveValueColumnText(variable, preEditText);
        return normalizeValueColumnDisplay(preEditText).equals(normalizeValueColumnDisplay(current));
    }

    private static void scheduleDeferredExpressionValueRefresh(ChangeValueSession session,
        AbstractTreeViewer treeViewer, IBslVariable variable, String reason, int attempt)
    {
        if (session == null || treeViewer == null || variable == null
            || attempt >= DEFERRED_VALUE_COLUMN_MAX_ATTEMPTS)
        {
            // #region agent log
            if (attempt >= DEFERRED_VALUE_COLUMN_MAX_ATTEMPTS)
                agentLog("U", "scheduleDeferredExpressionValueRefresh", "deferred-gave-up", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "reason", reason, "variable", describe(variable)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        Control control = treeViewer.getControl();
        if (control == null || control.isDisposed())
            return;
        Display display = control.getDisplay();
        if (display == null || display.isDisposed())
            return;
        final String preEdit = session.preEditValueColumnText;
        display.timerExec(DEFERRED_VALUE_COLUMN_DELAY_MS, () ->
        {
            if (control.isDisposed())
                return;
            IBslVariable live = selectedVariable(treeViewer);
            IBslVariable target = live != null ? live : variable;
            if (!valueColumnMatchesPreEdit(target, preEdit))
            {
                applyTargetedUpdate(session.viewer, session.contentProvider, target,
                    reason + "-deferred-" + attempt); //$NON-NLS-1$
                // #region agent log
                agentLog("U", "scheduleDeferredExpressionValueRefresh", "deferred-painted", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "attempt", String.valueOf(attempt), "variable", describe(target), //$NON-NLS-1$ //$NON-NLS-2$
                    "preEdit", preEdit != null ? preEdit : "", //$NON-NLS-1$ //$NON-NLS-2$
                    "current", resolveValueColumnText(target, "")); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return;
            }
            // #region agent log
            agentLog("U", "scheduleDeferredExpressionValueRefresh", "deferred-retry", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "attempt", String.valueOf(attempt), "variable", describe(target)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            scheduleDeferredExpressionValueRefresh(session, treeViewer, variable, reason, attempt + 1);
        });
    }

    private static boolean isExpressionsViewer(Viewer viewer)
    {
        return viewer != null && Boolean.TRUE.equals(viewer.getData(EXPRESSIONS_GUARD_KEY));
    }

    /** {@code InternalTreeModelViewer.update} трогает только STATE — колонку «Значение» обновляет {@code updateElement}. */
    private static boolean refreshVariableColumnLabels(Viewer viewer, Object contentProvider,
        IBslVariable variable, String reason)
    {
        try
        {
            Object pathsObj = Global.invoke(viewer, "getElementPaths", variable); //$NON-NLS-1$
            if (!(pathsObj instanceof TreePath[] paths) || paths.length == 0)
            {
                // #region agent log
                agentLog("I", "refreshVariableColumnLabels", "no-paths", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "variable", describe(variable)); //$NON-NLS-1$
                // #endregion
                return false;
            }
            int[] columns = valueColumnIndices(viewer);
            if (columns.length == 0)
                columns = new int[] { 1 };
            for (TreePath path : paths)
            {
                for (int column : columns)
                    Global.invoke(contentProvider, "updateElement", path, column); //$NON-NLS-1$
            }
            // #region agent log
            agentLog("I", "refreshVariableColumnLabels", "updateElement", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "reason", reason, "variable", describe(variable), //$NON-NLS-1$ //$NON-NLS-2$
                "pathCount", String.valueOf(paths.length), "col0", String.valueOf(columns[0])); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return true;
        }
        catch (Exception e)
        {
            DebugExpressionsDebug.problem("refreshVariableColumnLabels: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static int[] valueColumnIndices(Viewer viewer)
    {
        try
        {
            Object colsObj = Global.invoke(viewer, "getVisibleColumns"); //$NON-NLS-1$
            if (!(colsObj instanceof String[] cols))
                return new int[0];
            int[] result = new int[cols.length];
            int count = 0;
            for (int i = 0; i < cols.length; i++)
            {
                if (COL_VAR_VALUE.equals(cols[i]))
                    result[count++] = i;
            }
            if (count == 0)
                return new int[0];
            int[] trimmed = new int[count];
            System.arraycopy(result, 0, trimmed, 0, count);
            return trimmed;
        }
        catch (Exception e)
        {
            return new int[0];
        }
    }

    /** Как {@code ExpressionEventHandler} для одной переменной — без refresh корня ExpressionManager. */
    private static void applyVariableContentDelta(Object contentProvider, IBslVariable variable, Viewer viewer)
    {
        if (contentProvider == null || variable == null)
            return;
        try
        {
            Class<?> deltaClass = Class.forName(
                "org.eclipse.debug.internal.ui.viewers.model.provisional.ModelDelta"); //$NON-NLS-1$
            Object manager = DebugPlugin.getDefault().getExpressionManager();
            Object rootDelta = deltaClass.getConstructor(Object.class, int.class).newInstance(manager, 0);
            int flags = 1024 | 2048; // CONTENT | STATE — подписи колонок и detail
            deltaClass.getMethod("addNode", Object.class, int.class).invoke(rootDelta, variable, flags); //$NON-NLS-1$
            Global.invoke(contentProvider, "updateModel", rootDelta, DEFAULT_MODEL_DELTA_MASK); //$NON-NLS-1$
            // #region agent log
            agentLog("H", "applyVariableContentDelta", "delta-applied", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "variable", describe(variable), //$NON-NLS-1$
                "expressions", String.valueOf(isExpressionsViewer(viewer))); //$NON-NLS-1$
            // #endregion
        }
        catch (Exception e)
        {
            DebugExpressionsDebug.problem("applyVariableContentDelta: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static Object testFindItem(AbstractTreeViewer treeViewer, Object element)
    {
        try
        {
            return Global.invoke(treeViewer, "findItem", element); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static Object stockTreeModelContentProvider(Viewer viewer)
    {
        AbstractTreeViewer treeViewer = asTreeViewer(viewer);
        if (treeViewer == null)
            return null;
        IContentProvider provider = treeViewer.getContentProvider();
        if (provider == null)
            return null;
        return provider.getClass().getName().endsWith("TreeModelContentProvider") ? provider : null; //$NON-NLS-1$
    }

    private static int modelDeltaMask(Object contentProvider)
    {
        if (contentProvider == null)
            return DEFAULT_MODEL_DELTA_MASK;
        try
        {
            Object value = Global.invoke(contentProvider, "getModelDeltaMask"); //$NON-NLS-1$
            return value instanceof Integer mask ? mask.intValue() : DEFAULT_MODEL_DELTA_MASK;
        }
        catch (Exception e)
        {
            DebugExpressionsDebug.problem("getModelDeltaMask: " + e.getMessage()); //$NON-NLS-1$
            return DEFAULT_MODEL_DELTA_MASK;
        }
    }

    private static void setModelDeltaMask(Object contentProvider, int mask)
    {
        if (contentProvider == null)
            return;
        try
        {
            Global.invoke(contentProvider, "setModelDeltaMask", mask); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DebugExpressionsDebug.problem("setModelDeltaMask: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void restoreModelDeltaMask(ChangeValueSession session)
    {
        restoreModelDeltaMask(session, false);
    }

    private static void restoreModelDeltaMask(ChangeValueSession session, boolean force)
    {
        if (session == null || session.contentProvider == null || session.modelDeltaMaskRestored)
            return;
        if (!force && isExpressionsView(session.debugView))
        {
            // #region agent log
            agentLog("N", "restoreModelDelta", "skipped-expressions", "post-fix"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            return;
        }
        setModelDeltaMask(session.contentProvider, session.previousModelDeltaMask);
        session.modelDeltaMaskRestored = true;
        // #region agent log
        agentLog("F", "restoreModelDelta", "mask-restored", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "mask", String.valueOf(session.previousModelDeltaMask), "forced", String.valueOf(force)); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private static void completeSession(ChangeValueSession session, String reason)
    {
        completeSession(session, reason, true, true);
    }

    private static void completeSession(ChangeValueSession session, String reason, boolean restoreListener)
    {
        completeSession(session, reason, restoreListener, true);
    }

    private static void completeSession(ChangeValueSession session, String reason, boolean restoreListener,
        boolean restoreMask)
    {
        if (session == null)
            return;
        synchronized (session)
        {
            if (session.completed)
                return;
            session.completed = true;
        }
        pendingSessions.remove(session.viewer);
        if (restoreListener && session.refreshSuppressed && !isExpressionsView(session.debugView))
            restoreStockRefresh(session.debugView);
        if (restoreMask && !session.modelDeltaMaskRestored)
            restoreModelDeltaMask(session);
        // #region agent log
        agentLog("C", "completeSession", "session-end", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "reason", reason, "pendingCount", String.valueOf(pendingSessions.size()), //$NON-NLS-1$ //$NON-NLS-2$
            "listenerRestored", String.valueOf(restoreListener), //$NON-NLS-1$
            "maskRestored", String.valueOf(restoreMask && session.modelDeltaMaskRestored)); //$NON-NLS-1$
        // #endregion
        DebugExpressionsDebug.step("changeValue", "end " + reason); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void scheduleSessionTimeout(ChangeValueSession session)
    {
        Display display = displayOf(session.viewer);
        if (display == null || display.isDisposed())
            return;
        display.timerExec(SESSION_TIMEOUT_MS, () ->
        {
            if (!session.completed && pendingSessions.get(session.viewer) == session)
                finishChangeValueSession(session, "timeout"); //$NON-NLS-1$
        });
    }

    /**
     * JFace {@code Dialog.open()} блокирует UI-поток: polling shell в {@code watchChangeValueDialog}
     * не успевает до {@code Dispose}. asyncExec выполняется после закрытия модального диалога.
     */
    private static void scheduleContextMenuDialogFinish(ChangeValueSession session)
    {
        if (session == null)
            return;
        session.awaitingModalDialog = true;
        Display display = displayOf(session.viewer);
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> runOnViewerDisplay(session.viewer, () ->
        {
            if (session.completed)
                return;
            session.awaitingModalDialog = false;
            session.dialogClosed = true;
            // #region agent log
            agentLog("V", "scheduleContextMenuDialogFinish", "dialog-async-close", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "variable", describe(session.targetVariable), //$NON-NLS-1$
                "valueChanged", String.valueOf(session.valueChanged)); //$NON-NLS-1$
            // #endregion
            if (session.valueChanged)
                scheduleFinishChangeValueSession(session, "dialog-close"); //$NON-NLS-1$
            else
            {
                Display backupDisplay = displayOf(session.viewer);
                if (backupDisplay != null && !backupDisplay.isDisposed())
                {
                    backupDisplay.timerExec(CONTEXT_MENU_CANCEL_FINISH_MS, () -> runOnViewerDisplay(session.viewer, () ->
                    {
                        if (session.completed)
                            return;
                        if (!session.valueChanged)
                        {
                            // #region agent log
                            agentLog("V", "scheduleContextMenuDialogFinish", "dialog-cancel-finish", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                "variable", describe(session.targetVariable)); //$NON-NLS-1$
                            // #endregion
                            scheduleFinishChangeValueSession(session, "dialog-close"); //$NON-NLS-1$
                        }
                    }));
                }
            }
        }));
    }

    private static void watchChangeValueDialog(ChangeValueSession session, int attempt)
    {
        if (session == null || session.completed || attempt > DIALOG_WATCH_MAX_ATTEMPTS)
            return;
        Display display = displayOf(session.viewer);
        if (display == null || display.isDisposed())
            return;
        Shell hooked = session.hookedDialogShell;
        if (hooked != null && !hooked.isDisposed())
        {
            display.timerExec(CELL_EDITOR_POLL_MS, () -> watchChangeValueDialog(session, attempt + 1));
            return;
        }
        for (Shell shell : display.getShells())
        {
            if (!isChangeValueDialogShell(shell))
                continue;
            hookChangeValueDialogClose(session, shell);
            display.timerExec(CELL_EDITOR_POLL_MS, () -> watchChangeValueDialog(session, attempt + 1));
            return;
        }
        display.timerExec(CELL_EDITOR_POLL_MS, () -> watchChangeValueDialog(session, attempt + 1));
    }

    private static void hookChangeValueDialogClose(ChangeValueSession session, Shell shell)
    {
        if (session == null || shell == null || shell.isDisposed() || session.completed)
            return;
        if (session.hookedDialogShell == shell)
            return;
        session.hookedDialogShell = shell;
        session.dialogWasOpen = true;
        // #region agent log
        agentLog("V", "hookChangeValueDialogClose", "dialog-hooked", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "shellClass", shell.getClass().getName(), //$NON-NLS-1$
            "title", shell.getText() != null ? shell.getText() : ""); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        shell.addListener(SWT.Dispose, e -> runOnViewerDisplay(session.viewer, () ->
        {
            if (session.completed)
                return;
            session.valueChanged = true;
            // #region agent log
            agentLog("V", "hookChangeValueDialogClose", "dialog-dispose", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "variable", describe(session.targetVariable)); //$NON-NLS-1$
            // #endregion
            scheduleFinishChangeValueSession(session, "dialog-close"); //$NON-NLS-1$
        }));
    }

    private static boolean isChangeValueDialogOpen(Viewer viewer)
    {
        Display display = displayOf(viewer);
        if (display == null || display.isDisposed())
            return false;
        for (Shell shell : display.getShells())
        {
            if (shell != null && !shell.isDisposed() && shell.isVisible() && isChangeValueDialogShell(shell))
                return true;
        }
        return false;
    }

    private static void watchCellEditorClose(ChangeValueSession session, int attempt)
    {
        if (session == null || session.completed || attempt > 600)
            return;
        if (!"inline-cell".equals(session.source)) //$NON-NLS-1$
            return;
        Display display = displayOf(session.viewer);
        if (display == null || display.isDisposed())
            return;
        Boolean active = isCellEditorActive(session.viewer);
        if (Boolean.TRUE.equals(active))
            session.cellEditorWasActive = true;
        else if (session.cellEditorWasActive)
        {
            session.valueChanged = true;
            scheduleFinishChangeValueSession(session, "cell-editor-close"); //$NON-NLS-1$
            return;
        }
        display.timerExec(CELL_EDITOR_POLL_MS, () -> watchCellEditorClose(session, attempt + 1));
    }

    private static Boolean isCellEditorActive(Viewer viewer)
    {
        if (viewer == null)
            return null;
        try
        {
            Object result = Global.invoke(viewer, "isCellEditorActive"); //$NON-NLS-1$
            return result instanceof Boolean active ? active : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Только по классу shell — заголовок EDT может меняться. */
    private static boolean isChangeValueDialogShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return false;
        String className = shell.getClass().getName();
        return className.contains("ChangeVariableValueInputDialog") //$NON-NLS-1$
            || className.contains("BslValueEditorDialog"); //$NON-NLS-1$
    }

    private static Display displayOf(Viewer viewer)
    {
        if (viewer == null)
            return null;
        Control control = viewer.getControl();
        if (control == null || control.isDisposed())
            return null;
        Display display = control.getDisplay();
        return display != null && !display.isDisposed() ? display : null;
    }

    private static void runOnViewerDisplay(Viewer viewer, Runnable task)
    {
        if (viewer == null || task == null)
            return;
        Display display = displayOf(viewer);
        if (display == null)
            return;
        if (display.getThread() == Thread.currentThread())
            task.run();
        else
            display.asyncExec(() ->
            {
                if (displayOf(viewer) != null)
                    task.run();
            });
    }

    private static IBslVariable selectedVariable(Viewer viewer)
    {
        if (viewer == null)
            return null;
        ISelection selection = viewer.getSelection();
        if (selection instanceof IStructuredSelection structured && structured.size() == 1)
        {
            Object element = structured.getFirstElement();
            if (element instanceof IBslVariable variable)
                return variable;
        }
        return null;
    }

    private static Tree treeForContextMenu(Menu menu)
    {
        Shell shell = menu.getShell();
        if (shell == null || shell.isDisposed())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                continue;
            IWorkbenchPart part = page.getActivePart();
            if (!(part instanceof AbstractDebugView debugView) || !isTargetView(debugView))
                continue;
            Viewer viewer = debugView.getViewer();
            if (viewer == null)
                continue;
            Tree tree = treeOf(viewer);
            if (tree != null && !tree.isDisposed() && tree.getShell() == shell)
                return tree;
        }
        return null;
    }

    private static AbstractDebugView debugViewForTree(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IWorkbenchPart part = page.getActivePart();
            if (part instanceof AbstractDebugView debugView && isTargetView(debugView))
            {
                Tree viewTree = treeOf(debugView.getViewer());
                if (viewTree == tree)
                    return debugView;
            }
        }
        catch (Exception ignored)
        {
            // active part may be unavailable during menu
        }
        return null;
    }

    private static Tree treeOf(Viewer viewer)
    {
        if (viewer instanceof AbstractTreeViewer treeViewer)
        {
            Control control = treeViewer.getControl();
            if (control instanceof Tree tree && !tree.isDisposed())
                return tree;
        }
        if (viewer == null)
            return null;
        try
        {
            Object treeObj = Global.invoke(viewer, "getTree"); //$NON-NLS-1$
            return treeObj instanceof Tree tree ? tree : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static AbstractTreeViewer asTreeViewer(Viewer viewer)
    {
        return viewer instanceof AbstractTreeViewer treeViewer ? treeViewer : null;
    }

    private static boolean isTargetView(AbstractDebugView view)
    {
        String id = view.getViewSite() != null ? view.getViewSite().getId() : null;
        return EXPRESSIONS_VIEW_ID.equals(id) || IDebugUIConstants.ID_VARIABLE_VIEW.equals(id);
    }

    private static String viewId(AbstractDebugView view)
    {
        return view.getViewSite() != null ? view.getViewSite().getId() : "?"; //$NON-NLS-1$
    }

    private static IPreferenceStore preferenceStore()
    {
        try
        {
            Class<?> pluginClass = Class.forName("org.eclipse.debug.internal.ui.DebugUIPlugin"); //$NON-NLS-1$
            Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (plugin == null)
                return null;
            Object store = pluginClass.getMethod("getPreferenceStore").invoke(plugin); //$NON-NLS-1$
            return store instanceof IPreferenceStore preferenceStore ? preferenceStore : null;
        }
        catch (Exception e)
        {
            DebugExpressionsDebug.problem("preferenceStore: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static boolean isChangeValueMenuItem(MenuItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        String label = item.getText();
        if (label != null)
        {
            String trimmed = stripMenuAccelerators(label.trim());
            if (trimmed.contains("Изменить значение") //$NON-NLS-1$
                || trimmed.contains("Change value") //$NON-NLS-1$
                || trimmed.contains("Change Value")) //$NON-NLS-1$
                return true;
        }
        Object data = item.getData();
        return data != null && data.getClass().getName().contains("ChangeVariableValueAction"); //$NON-NLS-1$
    }

    private static String stripMenuAccelerators(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        int tab = text.indexOf('\t');
        return tab >= 0 ? text.substring(0, tab) : text;
    }

    private static int treeRootItemCount(AbstractTreeViewer treeViewer)
    {
        Tree tree = treeOf(treeViewer);
        if (tree == null || tree.isDisposed())
            return 0;
        return tree.getItems().length;
    }

    private static TreeItem findTreeItemByWatchPath(TreeItem[] items, String watchPath)
    {
        if (items == null || watchPath == null || watchPath.isBlank())
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            Object data = item.getData();
            if (data instanceof IBslVariable variable)
            {
                String path = BslInspectSupport.resolveVariableInspectExpression(variable);
                if (watchPath.equals(path))
                    return item;
            }
            TreeItem nested = findTreeItemByWatchPath(item.getItems(), watchPath);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static TreePath treePathFromItem(TreeItem item)
    {
        if (item == null || item.isDisposed())
            return null;
        List<Object> segments = new ArrayList<>();
        TreeItem current = item;
        while (current != null && !current.isDisposed())
        {
            segments.add(0, current.getData());
            current = current.getParentItem();
        }
        return segments.isEmpty() ? null : new TreePath(segments.toArray());
    }

    private static String describe(IBslVariable variable)
    {
        if (variable == null)
            return "null"; //$NON-NLS-1$
        try
        {
            String name = variable.getName();
            if (name != null && !name.isBlank())
                return name;
        }
        catch (RuntimeException ignored)
        {
            // optional
        }
        return String.valueOf(variable);
    }

    /** Свёртки и выделение: сегменты {@code var:имя|[N]}, {@code expr:текст}; pin — watch-путь. */
    private static final class TreeStateSnapshot
    {
        private final List<String> selectionPath;
        private final List<List<String>> expandedPaths;
        /** Полный watch-путь, как в «Наблюдение» ({@link BslInspectSupport#resolveVariableInspectExpression}). */
        private final String selectionPinWatchPath;

        private TreeStateSnapshot(List<String> selectionPath, List<List<String>> expandedPaths,
            String selectionPinWatchPath)
        {
            this.selectionPath = selectionPath;
            this.expandedPaths = expandedPaths;
            this.selectionPinWatchPath = selectionPinWatchPath;
        }

        static TreeStateSnapshot capture(AbstractTreeViewer treeViewer)
        {
            if (treeViewer == null)
                return null;
            List<String> selection = null;
            String pinWatchPath = null;
            ISelection sel = treeViewer.getSelection();
            if (sel instanceof TreeSelection treeSelection)
            {
                TreePath[] paths = treeSelection.getPaths();
                if (paths != null && paths.length > 0)
                {
                    List<String> keys = pathKeys(paths[0]);
                    if (!keys.isEmpty())
                        selection = keys;
                }
            }
            IBslVariable pin = selectedVariable(treeViewer);
            if (pin != null)
            {
                pinWatchPath = BslInspectSupport.resolveVariableInspectExpression(pin);
                if (pinWatchPath != null && pinWatchPath.isBlank())
                    pinWatchPath = null;
            }
            List<List<String>> expanded = captureExpandedPaths(treeViewer);
            if (selection == null && expanded.isEmpty())
                return null;
            // #region agent log
            agentLog("R", "TreeStateSnapshot.capture", "path-keys", "post-fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "selectionPath", selection != null ? String.join("/", selection) : "", //$NON-NLS-1$ //$NON-NLS-2$
                "pinWatchPath", pinWatchPath != null ? pinWatchPath : ""); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return new TreeStateSnapshot(selection, expanded, pinWatchPath);
        }

        int pathDepth()
        {
            return selectionPath != null ? selectionPath.size() : 0;
        }

        int expandedPathCount()
        {
            return expandedPaths != null ? expandedPaths.size() : 0;
        }

        boolean matchesSelectionPin(IBslVariable selected)
        {
            if (selectionPinWatchPath == null || selectionPinWatchPath.isBlank())
                return true;
            if (selected == null)
                return false;
            String selectedPath = BslInspectSupport.resolveVariableInspectExpression(selected);
            return selectionPinWatchPath.equals(selectedPath);
        }

        String selectionPinWatchPath()
        {
            return selectionPinWatchPath;
        }

        String selectionPathText()
        {
            return selectionPath != null ? String.join("/", selectionPath) : ""; //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Другая ветка/свойство — пользователь сам сменил строку; предок целевого пути — нет. */
        boolean isUserIntentionalSelectionChange(IBslVariable selected)
        {
            if (selectionPinWatchPath == null || selectionPinWatchPath.isBlank())
                return false;
            if (matchesSelectionPin(selected))
                return false;
            if (selected == null)
                return false;
            String path = BslInspectSupport.resolveVariableInspectExpression(selected);
            if (path == null || path.isBlank())
                return false;
            if (selectionPinWatchPath.startsWith(path + ".") || selectionPinWatchPath.startsWith(path + "[")) //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            return true;
        }

        enum MaterializeState
        {
            READY,
            WAITING_CHILDREN,
            MISSING_PREFIX
        }

        void materializeExpandedPaths(AbstractTreeViewer treeViewer)
        {
            Tree tree = treeOf(treeViewer);
            if (tree == null || tree.isDisposed() || expandedPaths == null || expandedPaths.isEmpty())
                return;
            tree.setRedraw(false);
            try
            {
                List<TreePath> builtExpanded = new ArrayList<>();
                for (List<String> pathKeys : expandedPaths)
                {
                    materializePathPrefix(treeViewer, tree, pathKeys);
                    TreePath built = buildPath(tree, pathKeys);
                    if (built != null)
                        builtExpanded.add(built);
                }
                if (!builtExpanded.isEmpty())
                    treeViewer.setExpandedTreePaths(builtExpanded.toArray(new TreePath[0]));
            }
            finally
            {
                tree.setRedraw(true);
            }
        }

        MaterializeState selectionMaterializeState(AbstractTreeViewer treeViewer)
        {
            if (selectionPath == null || selectionPath.isEmpty())
                return MaterializeState.READY;
            Tree tree = treeOf(treeViewer);
            if (tree == null || tree.isDisposed())
                return MaterializeState.MISSING_PREFIX;
            return materializePathPrefix(treeViewer, tree, selectionPath);
        }

        void nudgeSelectionPathChildren(AbstractTreeViewer treeViewer)
        {
            if (selectionPath == null || selectionPath.size() < 2)
                return;
            Tree tree = treeOf(treeViewer);
            if (tree == null || tree.isDisposed())
                return;
            List<String> parentKeys = selectionPath.subList(0, selectionPath.size() - 1);
            TreePath parentPath = buildPath(tree, parentKeys);
            if (parentPath == null)
                return;
            Object parent = parentPath.getLastSegment();
            if (parent == null)
                return;
            if (!treeViewer.getExpandedState(parent))
                treeViewer.setExpandedState(parent, true);
            else
            {
                try
                {
                    treeViewer.update(parent, null);
                }
                catch (RuntimeException ignored)
                {
                    // optional
                }
            }
            treeViewer.reveal(parent);
        }

        boolean applySelection(AbstractTreeViewer treeViewer, SelectionRestoreGuard guard)
        {
            if (selectionPath == null || selectionPath.isEmpty())
                return selectionPinWatchPath == null || selectionPinWatchPath.isBlank();
            if (selectionMaterializeState(treeViewer) != MaterializeState.READY)
                return false;
            Tree tree = treeOf(treeViewer);
            if (tree == null || tree.isDisposed())
                return false;
            TreePath selection = resolveSelectionTreePath(tree);
            if (selection == null)
                return false;
            TreePath finalSelection = selection;
            Runnable select = () ->
            {
                treeViewer.setSelection(new TreeSelection(finalSelection), true);
                Object last = finalSelection.getLastSegment();
                if (last != null)
                    treeViewer.reveal(last);
            };
            if (guard != null)
                guard.runProgrammatic(select);
            else
                select.run();
            return true;
        }

        TreePath resolveSelectionTreePath(Tree tree)
        {
            if (tree == null || tree.isDisposed())
                return null;
            TreePath selection = null;
            if (selectionPath != null && !selectionPath.isEmpty())
            {
                selection = buildPath(tree, selectionPath);
                if (selection != null)
                {
                    Object last = selection.getLastSegment();
                    if (last instanceof IBslVariable variable && !matchesSelectionPin(variable))
                        selection = null;
                }
            }
            if (selection == null && selectionPinWatchPath != null && !selectionPinWatchPath.isBlank())
            {
                TreeItem item = findTreeItemByWatchPath(tree.getItems(), selectionPinWatchPath);
                selection = treePathFromItem(item);
            }
            return selection;
        }

        boolean isTargetRowReady(AbstractTreeViewer treeViewer)
        {
            if (selectionPinWatchPath == null || selectionPinWatchPath.isBlank())
                return selectionPath == null || selectionPath.isEmpty();
            Tree tree = treeOf(treeViewer);
            if (tree == null || tree.isDisposed())
                return false;
            if (!matchesSelectionPin(selectedVariable(treeViewer)))
                return false;
            TreeItem item = findTreeItemByWatchPath(tree.getItems(), selectionPinWatchPath);
            return item != null && !item.isDisposed();
        }

        /** Раскрывает префикс пути; {@code WAITING_CHILDREN} — lazy-дети ещё не в SWT. */
        private static MaterializeState materializePathPrefix(AbstractTreeViewer treeViewer, Tree tree,
            List<String> pathKeys)
        {
            if (pathKeys == null || pathKeys.isEmpty())
                return MaterializeState.READY;
            TreeItem[] level = tree.getItems();
            for (int i = 0; i < pathKeys.size(); i++)
            {
                if ((level == null || level.length == 0) && i < pathKeys.size())
                    return MaterializeState.WAITING_CHILDREN;
                TreeItem item = findAmong(level, pathKeys.get(i));
                if (item == null)
                {
                    if (i == 0 && (level == null || level.length == 0))
                        return MaterializeState.WAITING_CHILDREN;
                    return MaterializeState.MISSING_PREFIX;
                }
                if (i < pathKeys.size() - 1)
                {
                    Object data = item.getData();
                    if (!treeViewer.getExpandedState(data))
                        treeViewer.setExpandedState(data, true);
                    level = item.getItems();
                    if (level == null || level.length == 0)
                        return MaterializeState.WAITING_CHILDREN;
                }
            }
            return MaterializeState.READY;
        }

        boolean restore(AbstractTreeViewer treeViewer)
        {
            Tree tree = treeOf(treeViewer);
            if (tree == null || tree.isDisposed())
                return false;

            tree.setRedraw(false);
            try
            {
                if (expandedPaths != null && !expandedPaths.isEmpty())
                {
                    List<TreePath> builtExpanded = new ArrayList<>();
                    for (List<String> pathKeys : expandedPaths)
                    {
                        ensurePathMaterialized(treeViewer, tree, pathKeys);
                        TreePath built = buildPath(tree, pathKeys);
                        if (built != null)
                            builtExpanded.add(built);
                    }
                    if (!builtExpanded.isEmpty())
                        treeViewer.setExpandedTreePaths(builtExpanded.toArray(new TreePath[0]));
                }
                if (selectionPath == null || selectionPath.isEmpty())
                    return expandedPaths != null && !expandedPaths.isEmpty();
                if (!ensurePathMaterialized(treeViewer, tree, selectionPath))
                    return false;
                TreePath selection = buildPath(tree, selectionPath);
                if (selection == null)
                    return false;
                treeViewer.setSelection(new TreeSelection(selection), true);
                Object last = selection.getLastSegment();
                if (last != null)
                    treeViewer.reveal(last);
                return true;
            }
            finally
            {
                tree.setRedraw(true);
            }
        }

        private static boolean ensurePathMaterialized(AbstractTreeViewer treeViewer, Tree tree,
            List<String> pathKeys)
        {
            if (pathKeys == null || pathKeys.isEmpty())
                return true;
            TreeItem[] level = tree.getItems();
            for (int i = 0; i < pathKeys.size(); i++)
            {
                TreeItem item = findAmong(level, pathKeys.get(i));
                if (item == null)
                    return false;
                Object data = item.getData();
                if (i < pathKeys.size() - 1)
                {
                    if (!treeViewer.getExpandedState(data))
                        treeViewer.setExpandedState(data, true);
                    level = item.getItems();
                    if (level == null || level.length == 0)
                        return false;
                }
            }
            return true;
        }

        private static List<List<String>> captureExpandedPaths(AbstractTreeViewer treeViewer)
        {
            List<List<String>> expanded = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            TreePath[] paths = treeViewer.getExpandedTreePaths();
            if (paths != null)
            {
                for (TreePath path : paths)
                {
                    List<String> keys = pathKeys(path);
                    if (!keys.isEmpty() && seen.add(pathKeyId(keys)))
                        expanded.add(keys);
                }
            }
            Tree tree = treeOf(treeViewer);
            if (tree != null && !tree.isDisposed())
                collectExpandedTreeItems(tree.getItems(), new ArrayList<>(), expanded, seen);
            return expanded;
        }

        private static void collectExpandedTreeItems(TreeItem[] items, List<String> prefix,
            List<List<String>> expanded, LinkedHashSet<String> seen)
        {
            if (items == null)
                return;
            for (TreeItem item : items)
            {
                if (item == null || item.isDisposed())
                    continue;
                List<String> path = new ArrayList<>(prefix);
                path.add(TreeElementKey.forData(item.getData()));
                if (item.getExpanded())
                {
                    if (seen.add(pathKeyId(path)))
                        expanded.add(path);
                    collectExpandedTreeItems(item.getItems(), path, expanded, seen);
                }
            }
        }

        private static String pathKeyId(List<String> keys)
        {
            return String.join("\u0001", keys); //$NON-NLS-1$
        }

        private static List<String> pathKeys(TreePath path)
        {
            List<String> keys = new ArrayList<>();
            if (path == null)
                return keys;
            for (int i = 0; i < path.getSegmentCount(); i++)
                keys.add(TreeElementKey.forElement(path.getSegment(i)));
            return keys;
        }

        private static TreePath buildPath(Tree tree, List<String> pathKeys)
        {
            if (pathKeys == null || pathKeys.isEmpty())
                return null;
            List<Object> segments = new ArrayList<>();
            TreeItem[] level = tree.getItems();
            for (String key : pathKeys)
            {
                TreeItem item = findAmong(level, key);
                if (item == null)
                    return null;
                segments.add(item.getData());
                level = item.getItems();
            }
            return new TreePath(segments.toArray());
        }

        private static void expandPathKeys(AbstractTreeViewer treeViewer, Tree tree, List<String> pathKeys)
        {
            ensurePathMaterialized(treeViewer, tree, pathKeys);
        }

        private static TreeItem findAmong(TreeItem[] items, String key)
        {
            if (items == null || key == null)
                return null;
            for (TreeItem item : items)
            {
                if (item == null || item.isDisposed())
                    continue;
                if (key.equals(TreeElementKey.forData(item.getData())))
                    return item;
            }
            return null;
        }
    }

    private static final class TreeElementKey
    {
        private TreeElementKey() {}

        static String forElement(Object element)
        {
            return forData(element);
        }

        static String forData(Object data)
        {
            if (data instanceof IBslVariable variable)
                return keyForVariable(variable);
            if (data instanceof IWatchExpression watch)
                return keyForWatch(watch);
            if (data instanceof IExpression expression)
            {
                String text = expression.getExpressionText();
                if (text != null && !text.isBlank())
                    return "expr:" + text; //$NON-NLS-1$
            }
            return "id:" + System.identityHashCode(data); //$NON-NLS-1$
        }

        private static String keyForVariable(IBslVariable variable)
        {
            String segment = variableSegmentKey(variable);
            if (segment.isEmpty())
                return "var:?"; //$NON-NLS-1$
            return "var:" + segment; //$NON-NLS-1$
        }

        /** Имя свойства или индекс {@code [N]} — как в дереве и «Наблюдение», без uuid. */
        static String variableSegmentKey(IBslVariable variable)
        {
            if (variable == null)
                return ""; //$NON-NLS-1$
            try
            {
                String name = variable.getName();
                if (name != null && !name.isBlank())
                    return name.trim();
            }
            catch (Exception ignored)
            {
                // optional
            }
            try
            {
                IBslValue value = variable.getValue();
                BslValuePath path = value != null ? value.getPath() : null;
                if (path != null)
                {
                    List<?> items = path.getPropertiesAndIndexes();
                    if (items != null && !items.isEmpty())
                    {
                        Object last = items.get(items.size() - 1);
                        if (last != null)
                        {
                            String segment = last.toString();
                            if (segment != null && !segment.isBlank())
                            {
                                segment = segment.trim();
                                if (segment.matches("\\d+")) //$NON-NLS-1$
                                    return "[" + segment + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                                return segment;
                            }
                        }
                    }
                }
            }
            catch (Exception ignored)
            {
                // optional
            }
            return ""; //$NON-NLS-1$
        }

        private static String keyForWatch(IWatchExpression watch)
        {
            if (watch instanceof IExpression expression)
            {
                String text = expression.getExpressionText();
                if (text != null && !text.isBlank())
                    return "expr:" + text; //$NON-NLS-1$
            }
            return "watch:" + System.identityHashCode(watch); //$NON-NLS-1$
        }
    }

    private static final class ChangeValueSession
    {
        final AbstractDebugView debugView;
        final Viewer viewer;
        final IBslVariable targetVariable;
        final Object contentProvider;
        final int previousModelDeltaMask;
        final boolean refreshSuppressed;
        final TreeStateSnapshot treeStateSnapshot;
        final String source;
        volatile boolean completed;
        volatile boolean valueChanged;
        volatile IBslVariable lastChangedVariable;
        volatile int finishGeneration;
        volatile boolean cellEditorWasActive;
        volatile boolean dialogWasOpen;
        volatile boolean awaitingModalDialog;
        volatile boolean dialogClosed;
        volatile Shell hookedDialogShell;
        volatile boolean modelDeltaMaskRestored;
        String preEditValueColumnText;

        ChangeValueSession(AbstractDebugView debugView, Viewer viewer, IBslVariable targetVariable,
            Object contentProvider, int previousModelDeltaMask, TreeStateSnapshot treeStateSnapshot, String source)
        {
            this.debugView = debugView;
            this.viewer = viewer;
            this.targetVariable = targetVariable;
            this.contentProvider = contentProvider;
            this.previousModelDeltaMask = previousModelDeltaMask;
            this.treeStateSnapshot = treeStateSnapshot;
            this.source = source;
            this.refreshSuppressed = true;
        }
    }
}
