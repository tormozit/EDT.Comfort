package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Загрузка коллекции по async-схеме штатной панели «Значения»:
 * kick {@code indexedValue.getVariables()} в core, viewport — {@code getVariables(from, count)},
 * refresh по {@code DebugEvent.CHANGE} detail 256/512 на {@link IBslIndexedValue}.
 */
final class DebugCollectionLoadScheduler
{
    private static final long DEBUG_REFRESH_DELAY_MS = 150L;
    private static final long MIN_DEBUG_REFRESH_INTERVAL_MS = 400L;
    private static final long PENDING_DIRTY_DEBOUNCE_MS = 500L;
    private static final long VIEWPORT_KICK_DELAY_MS = 150L;
    private static final int DEBUG_DETAIL_STATE = 256;
    private static final int DEBUG_DETAIL_CONTENT = 512;
    private static final int OVERSCAN = 8;
    private static final int CONTEXT_RESOLVE_MAX_ATTEMPTS = 2;

    interface ProgressListener
    {
        void onProgress(int loaded, int total, String phase);

        void onRowsReady(int displayFirst, int displayCount);

        /** Точечная перерисовка одной logical-строки (как EDT viewer.update). */
        void onRepaintLogicalRow(int logicalRow);
    }

    private final DebugCollectionTableModel model;
    private final Display display;
    private final ProgressListener progress;
    private final Consumer<IBslVariable[]> contextColumnsListener;
    private Table tableForViewport;
    private Shell shellForPause;
    private final java.util.concurrent.atomic.AtomicBoolean disposed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger viewportFirst = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger viewportLast = new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicBoolean shellVisible = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicBoolean shellMinimized = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Listener shellStateListener;
    private volatile Job variablesJob;
    private volatile Job filterJob;
    private volatile Job contextJob;
    private volatile Job viewportKickJob;
    private volatile Job inputUpdateJob;
    private final java.util.concurrent.atomic.AtomicInteger lastKickedFirst =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger lastKickedLast =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger contextResolveAttempts =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger sizeRowFrom =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizeRowTo =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizeColFrom =
        new java.util.concurrent.atomic.AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicInteger sizeColTo =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizePassGeneration =
        new java.util.concurrent.atomic.AtomicInteger();
    private IDebugEventSetListener debugEventListener;
    private Runnable debugRefreshDebounce;
    private Runnable viewportKickDebounce;
    private Runnable inputUpdateDebounce;
    private final java.util.concurrent.atomic.AtomicBoolean initialLoadSettled =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger debugEventCoalesceCount =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean debugRefreshArmed =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean priorityKickScheduled =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.BitSet dirtyLogicalRows = new java.util.BitSet();
    private final Object dirtyRowsLock = new Object();
    private volatile long lastDebugRefreshAt;
    private Runnable pendingDirtyDebounce;
    private Runnable priorityViewportRunnable;

    DebugCollectionLoadScheduler(
        DebugCollectionTableModel model,
        Display display,
        ProgressListener progress,
        Consumer<IBslVariable[]> contextColumnsListener)
    {
        this.model = model;
        this.display = display;
        this.progress = progress;
        this.contextColumnsListener = contextColumnsListener;
        DebugCollectionSizeResolver.resetForNewWindow();
        contextResolveAttempts.set(0);
        model.setDirtyRowHandler(this::markDirtyLogicalRowDebounced);
    }

    void bindTable(Table table)
    {
        tableForViewport = table;
    }

    void bindShell(Shell shell)
    {
        removeShellStateListener();
        shellForPause = shell;
        if (shell == null || shell.isDisposed())
        {
            shellVisible.set(false);
            shellMinimized.set(false);
            return;
        }
        updateShellActiveSnapshot();
        shellStateListener = e -> {
            if (e.type == SWT.Hide)
            {
                Display d = shell.getDisplay();
                if (d != null && !d.isDisposed())
                    d.asyncExec(this::updateShellActiveSnapshot);
                return;
            }
            updateShellActiveSnapshot();
        };
        shell.addListener(SWT.Show, shellStateListener);
        shell.addListener(SWT.Hide, shellStateListener);
        shell.addListener(SWT.Iconify, shellStateListener);
        shell.addListener(SWT.Deiconify, shellStateListener);
    }

    private void updateShellActiveSnapshot()
    {
        Shell shell = shellForPause;
        if (shell == null || shell.isDisposed())
        {
            shellVisible.set(false);
            shellMinimized.set(false);
            return;
        }
        shellVisible.set(shell.getVisible());
        shellMinimized.set(shell.getMinimized());
    }

    void scheduleInitialLoad()
    {
        DebugCollectionAgentLog.beginSession();
        initialLoadSettled.set(false);
        debugEventCoalesceCount.set(0);
        synchronized (dirtyRowsLock)
        {
            dirtyLogicalRows.clear();
        }
        updateShellActiveSnapshot();
        viewportFirst.set(0);
        viewportLast.set(OVERSCAN);
        installDebugEventListener();
        scheduleVariablesJob("initial", true); //$NON-NLS-1$
    }

    void scheduleFromClone(int logicalFirst, int logicalLast)
    {
        DebugCollectionAgentLog.beginSession();
        initialLoadSettled.set(true);
        debugEventCoalesceCount.set(0);
        updateShellActiveSnapshot();
        captureViewport(logicalFirst, logicalLast);
        installDebugEventListener();
        if (model.totalSize < 0)
            scheduleVariablesJob("clone"); //$NON-NLS-1$
        else
            fireProgress(model.loadedRowCount, model.totalSize, "rows"); //$NON-NLS-1$
        refreshViewportRows();
    }

    void requestViewport(int first, int last)
    {
        if (disposed.get() || !isShellActiveForLoad())
            return;
        captureViewport(first, last);
        scheduleViewportKickDebounced();
        scheduleSizePass();
    }

    /** Немедленный kick видимой страницы (End, большой скачок) — coalesced {@link Job#INTERACTIVE}. */
    void requestViewportPriority(int first, int last)
    {
        if (disposed.get() || !isShellActiveForLoad())
            return;
        if (viewportKickDebounce != null && display != null && !display.isDisposed())
            display.timerExec(-1, viewportKickDebounce);
        viewportKickDebounce = null;
        captureViewport(first, last);
        schedulePriorityViewportKickCoalesced();
    }

    private void schedulePriorityViewportKickCoalesced()
    {
        if (display == null || display.isDisposed() || disposed.get())
            return;
        if (!priorityKickScheduled.compareAndSet(false, true))
            return;
        Runnable schedule = () -> {
            if (display.isDisposed() || disposed.get())
            {
                priorityKickScheduled.set(false);
                return;
            }
            if (priorityViewportRunnable != null)
                display.timerExec(-1, priorityViewportRunnable);
            priorityViewportRunnable = () -> {
                priorityViewportRunnable = null;
                priorityKickScheduled.set(false);
                if (disposed.get() || !isShellActiveForLoad())
                    return;
                submitViewportKickJob("priority", true); //$NON-NLS-1$
            };
            display.asyncExec(priorityViewportRunnable);
        };
        if (display.getThread() == Thread.currentThread())
            schedule.run();
        else
            display.asyncExec(schedule);
    }

    void captureColumnViewport()
    {
        // Схема EDT: колонки определяются при resolve context, не по viewport.
    }

    void captureSizeViewport(int logicalFirst, int logicalLast, Table dataTable)
    {
        int total = model.totalSize;
        if (total > 0 && logicalLast >= total)
            logicalLast = total - 1;
        if (logicalFirst < 0)
            logicalFirst = 0;
        if (logicalLast < logicalFirst)
            logicalLast = logicalFirst;
        sizeRowFrom.set(logicalFirst);
        sizeRowTo.set(logicalLast);

        int maxCol = Math.max(0, model.columns.columnCount() - 1);
        int colFrom = 1;
        int colTo = maxCol;
        if (dataTable != null && !dataTable.isDisposed())
        {
            int[] cols = DebugCollectionViewportTracker.visibleModelColumnRange(
                dataTable, model.columns.columnCount(), model.columns.fixedColumnCount());
            colFrom = Math.min(maxCol, Math.max(1, cols[0]));
            colTo = Math.min(maxCol, cols[1]);
        }
        sizeColFrom.set(colFrom);
        sizeColTo.set(colTo);
    }

    void scheduleSizePass()
    {
        if (disposed.get() || !isShellActiveForLoad())
            return;
        if (model.totalSize == 0)
            return;
        if (display == null || display.isDisposed())
            return;
        int gen = sizePassGeneration.incrementAndGet();
        display.asyncExec(() -> {
            if (disposed.get() || gen != sizePassGeneration.get())
                return;
            executeSizePass();
        });
    }

    private void executeSizePass()
    {
        int rowFrom = sizeRowFrom.get();
        int rowTo = sizeRowTo.get();
        int colFrom = sizeColFrom.get();
        int colTo = sizeColTo.get();
        if (rowFrom < 0 || rowTo < rowFrom || colTo < colFrom)
            return;
        int rowCount = rowTo - rowFrom + 1;
        final int readyFrom = rowFrom;
        final int readyCount = rowCount;
        DebugCollectionDebug.step("load", //$NON-NLS-1$
            "size rows=" + rowFrom + ".." + rowTo + " cols=" + colFrom + ".." + colTo); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        DebugCollectionSizeResolver.scheduleBatch(model, rowFrom, rowCount, colFrom, colTo, display,
            () -> fireRowsReady(readyFrom, readyCount));
    }

    boolean isLoadActive()
    {
        return isJobBusy(variablesJob);
    }

    boolean isAutoPrefetchComplete()
    {
        return model.isRowsLoaded();
    }

    void scheduleFilterScan(DebugCollectionRowFilter filter, Runnable onDone)
    {
        if (disposed.get() || filter == null)
            return;
        cancelJob(filterJob);
        filterJob = Job.create("Комфорт: фильтр коллекции", monitor -> { //$NON-NLS-1$
            runFilterScan(filter, monitor, onDone);
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        filterJob.setSystem(true);
        filterJob.schedule();
    }

    void cancelFilterScan()
    {
        cancelJob(filterJob);
    }

    void resetLoadJobForSchemaChange()
    {
        model.invalidateAllCells();
        if (model.isRowsLoaded())
            repaintBoundTable();
        else
        {
            scheduleCollectionRekick("schema"); //$NON-NLS-1$
            refreshBoundTable();
        }
    }

    void cancelAll()
    {
        disposed.set(true);
        shellVisible.set(false);
        sizePassGeneration.incrementAndGet();
        removeShellStateListener();
        removeDebugEventListener();
        DebugCollectionSizeResolver.cancelAll();
        cancelJob(variablesJob);
        cancelJob(filterJob);
        cancelJob(contextJob);
        cancelJob(viewportKickJob);
        cancelJob(inputUpdateJob);
    }

    private void captureViewport(int logicalFirst, int logicalLast)
    {
        int total = model.totalSize;
        if (total > 0 && logicalLast >= total)
            logicalLast = total - 1;
        if (logicalFirst < 0)
            logicalFirst = 0;
        if (logicalLast < logicalFirst)
            logicalLast = logicalFirst;
        viewportFirst.set(Math.max(0, logicalFirst - OVERSCAN));
        viewportLast.set(logicalLast + OVERSCAN);
    }

    private void scheduleVariablesJob(String reason)
    {
        scheduleVariablesJob(reason, false);
    }

    private void scheduleVariablesJob(String reason, boolean scheduleContextAfter)
    {
        if (disposed.get() || !isShellActiveForLoad())
            return;
        cancelJob(variablesJob);
        variablesJob = Job.create("Комфорт: коллекция", monitor -> { //$NON-NLS-1$
            if (disposed.get() || monitor.isCanceled() || !isShellActiveForLoad())
                return org.eclipse.core.runtime.Status.OK_STATUS;
            DebugCollectionDebug.step("load.order", reason + ": variablesJob start"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                int size = model.indexedValue.getSize();
                model.totalSize = size;
                fireProgress(0, size, "size"); //$NON-NLS-1$
                if (size == 0)
                {
                    model.noteCollectionKicked(0);
                    fireProgress(0, 0, "rows"); //$NON-NLS-1$
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                }
                if ("initial".equals(reason)) //$NON-NLS-1$
                    model.clearLiveRowCache();
                if (model.loadedRowCount < size)
                {
                    IBslVariable[] vars = model.indexedValue.getVariables();
                    int varCount = vars != null ? vars.length : 0;
                    model.noteCollectionKicked(size);
                    DebugCollectionDebug.step("load.rows", //$NON-NLS-1$
                        reason + " totalSize=" + size + " vars.length=" + varCount); //$NON-NLS-1$ //$NON-NLS-2$
                    // #region agent log
                    DebugCollectionAgentLog.log("H-edtKick", "LoadScheduler.scheduleVariablesJob", "collectionKick", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "{\"reason\":\"" + reason + "\",\"totalSize\":" + size //$NON-NLS-1$ //$NON-NLS-2$
                            + ",\"varsLength\":" + varCount + ",\"mapSize\":" + 0 + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    // #endregion
                    logRowVariableSample();
                }
                fireProgress(model.loadedRowCount, size, "rows"); //$NON-NLS-1$
                initialLoadSettled.set(true);
                DebugCollectionDebug.step("load", reason + " rows=" + size); //$NON-NLS-1$ //$NON-NLS-2$
                if (scheduleContextAfter)
                    scheduleContextJob();
                requestViewportPriority(viewportFirst.get(), viewportLast.get());
                repaintBoundTable();
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("getVariables: " + e.getMessage()); //$NON-NLS-1$
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        variablesJob.setSystem(true);
        variablesJob.schedule();
    }

    private void logRowVariableSample()
    {
        StringBuilder sb = new StringBuilder();
        for (int row : new int[] { 0, 23, 24 })
        {
            if (model.totalSize > 0 && row >= model.totalSize)
                continue;
            try
            {
                IBslVariable variable = model.indexedValue != null
                    ? model.indexedValue.getVariable(row) : null;
                String name = variable != null ? variable.getName() : "null"; //$NON-NLS-1$
                if (sb.length() > 0)
                    sb.append(','); //$NON-NLS-1$
                sb.append(" i=").append(row).append(" name=").append(name); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("rowSample: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        if (sb.length() > 0)
            DebugCollectionDebug.step("load.rows", "rowSample" + sb); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void installDebugEventListener()
    {
        if (debugEventListener != null || disposed.get())
            return;
        debugEventListener = events -> {
            if (disposed.get() || !isShellActiveForLoad())
                return;
            IBslIndexedValue indexed = model.indexedValue;
            if (indexed == null)
                return;
            IDebugTarget collectionTarget = debugTargetOf(indexed);
            boolean sawContent = false;
            boolean sawState = false;
            boolean sawRoot = false;
            for (org.eclipse.debug.core.DebugEvent event : events)
            {
                if (event.getKind() != org.eclipse.debug.core.DebugEvent.CHANGE)
                    continue;
                Object source = event.getSource();
                if (!isRelevantDebugSource(source, indexed, collectionTarget))
                    continue;
                if (source == indexed || indexed.equals(source))
                    sawRoot = true;
                int detail = event.getDetail();
                if (detail == DEBUG_DETAIL_CONTENT)
                    sawContent = true;
                else if (detail == DEBUG_DETAIL_STATE)
                    sawState = true;
                markDirtyForDebugSource(source, indexed);
            }
            if (!sawContent && !sawState)
                return;
            debugEventCoalesceCount.incrementAndGet();
            if (sawContent && sawRoot && !initialLoadSettled.get())
                scheduleInputUpdateDebounced();
            if (sawContent || sawState)
                scheduleDebugRefreshDebounced();
        };
        DebugPlugin.getDefault().addDebugEventListener(debugEventListener);
        DebugCollectionDebug.step("load", "debugEventListener"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void removeDebugEventListener()
    {
        if (debugEventListener == null)
            return;
        DebugPlugin.getDefault().removeDebugEventListener(debugEventListener);
        debugEventListener = null;
    }

    private void scheduleDebugRefreshDebounced()
    {
        if (display == null || display.isDisposed())
            return;
        if (!debugRefreshArmed.compareAndSet(false, true))
            return;
        Runnable schedule = () -> {
            if (display.isDisposed() || disposed.get())
            {
                debugRefreshArmed.set(false);
                return;
            }
            if (debugRefreshDebounce != null)
                display.timerExec(-1, debugRefreshDebounce);
            debugRefreshDebounce = () -> {
                debugRefreshDebounce = null;
                debugRefreshArmed.set(false);
                if (disposed.get() || !isShellActiveForLoad())
                    return;
                long now = System.currentTimeMillis();
                long sinceLast = now - lastDebugRefreshAt;
                if (sinceLast < MIN_DEBUG_REFRESH_INTERVAL_MS)
                {
                    int wait = (int) (MIN_DEBUG_REFRESH_INTERVAL_MS - sinceLast);
                    if (!debugRefreshArmed.compareAndSet(false, true))
                        return;
                    display.timerExec(wait, () -> scheduleDebugRefreshDebounced());
                    return;
                }
                lastDebugRefreshAt = now;
                int coalesced = debugEventCoalesceCount.getAndSet(0);
                int dirtyCount = repaintDirtyLogicalRowsInViewport();
                if (dirtyCount <= 0)
                    return;
                DebugCollectionDebug.step("load", "debugRefresh"); //$NON-NLS-1$ //$NON-NLS-2$
                // #region agent log
                DebugCollectionAgentLog.log("H-refresh", "LoadScheduler.scheduleDebugRefreshDebounced", "uiRefresh", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"from\":" + viewportFirst.get() + ",\"to\":" + viewportLast.get() //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"eventBatches\":" + coalesced + ",\"dirtyRows\":" + dirtyCount + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // #endregion
            };
            display.timerExec((int) DEBUG_REFRESH_DELAY_MS, debugRefreshDebounce);
        };
        if (display.getThread() == Thread.currentThread())
            schedule.run();
        else
            display.asyncExec(schedule);
    }

    private void scheduleInputUpdateDebounced()
    {
        if (display == null || display.isDisposed() || disposed.get())
            return;
        Runnable schedule = () -> {
            if (display.isDisposed() || disposed.get())
                return;
            if (inputUpdateDebounce != null)
                display.timerExec(-1, inputUpdateDebounce);
            inputUpdateDebounce = () -> {
                inputUpdateDebounce = null;
                if (disposed.get() || !isShellActiveForLoad() || model.indexedValue == null)
                    return;
                cancelJob(inputUpdateJob);
                inputUpdateJob = Job.create("Комфорт: коллекция (input)", monitor -> { //$NON-NLS-1$
                    if (disposed.get() || monitor.isCanceled() || !isShellActiveForLoad())
                        return org.eclipse.core.runtime.Status.OK_STATUS;
                    try
                    {
                        model.indexedValue.getVariables();
                        DebugCollectionDebug.step("load", "inputUpdate: getVariables"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    catch (DebugException e)
                    {
                        DebugCollectionDebug.problem("inputUpdate getVariables: " + e.getMessage()); //$NON-NLS-1$
                    }
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                });
                inputUpdateJob.setSystem(true);
                inputUpdateJob.schedule();
            };
            display.timerExec((int) DEBUG_REFRESH_DELAY_MS, inputUpdateDebounce);
        };
        if (display.getThread() == Thread.currentThread())
            schedule.run();
        else
            display.asyncExec(schedule);
    }

    private void scheduleCollectionRekick(String reason)
    {
        if (disposed.get() || !isShellActiveForLoad() || model.indexedValue == null)
            return;
        lastKickedFirst.set(-1);
        lastKickedLast.set(-1);
        Job rekick = Job.create("Комфорт: коллекция (refresh)", monitor -> { //$NON-NLS-1$
            if (disposed.get() || monitor.isCanceled() || !isShellActiveForLoad())
                return org.eclipse.core.runtime.Status.OK_STATUS;
            try
            {
                model.indexedValue.getVariables();
                DebugCollectionDebug.step("load", reason + ": getVariables re-kick"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("rekick getVariables: " + e.getMessage()); //$NON-NLS-1$
            }
            scheduleViewportKickDebounced();
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        rekick.setSystem(true);
        rekick.schedule();
    }

    private void scheduleViewportKickDebounced()
    {
        if (display == null || display.isDisposed())
            return;
        Runnable schedule = () -> {
            if (display.isDisposed() || disposed.get())
                return;
            if (viewportKickDebounce != null)
                display.timerExec(-1, viewportKickDebounce);
            viewportKickDebounce = () -> {
                viewportKickDebounce = null;
                if (disposed.get() || !isShellActiveForLoad())
                    return;
                submitViewportKickJob("viewport", false); //$NON-NLS-1$
            };
            display.timerExec((int) VIEWPORT_KICK_DELAY_MS, viewportKickDebounce);
        };
        if (display.getThread() == Thread.currentThread())
            schedule.run();
        else
            display.asyncExec(schedule);
    }

    private void submitViewportKickJob(String reason, boolean priority)
    {
        int from = viewportFirst.get();
        int to = viewportLast.get();
        int total = model.totalSize;
        if (total <= 0 || to < from || model.indexedValue == null)
            return;
        if (from >= total)
            return;
        to = Math.min(to, total - 1);
        if (!priority && from == lastKickedFirst.get() && to == lastKickedLast.get())
            return;
        if (priority && isJobBusy(viewportKickJob)
            && from == lastKickedFirst.get() && to == lastKickedLast.get())
            return;
        lastKickedFirst.set(from);
        lastKickedLast.set(to);
        int count = to - from + 1;
        cancelJob(viewportKickJob);
        final int kickFrom = from;
        final int kickCount = count;
        viewportKickJob = Job.create("Комфорт: коллекция (viewport)", monitor -> { //$NON-NLS-1$
            if (disposed.get() || monitor.isCanceled() || !isShellActiveForLoad())
                return org.eclipse.core.runtime.Status.OK_STATUS;
            try
            {
                model.indexedValue.getVariables(kickFrom, kickCount);
                DebugCollectionDebug.step("load", reason + " page=" + kickFrom + "+" + kickCount); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // #region agent log
                DebugCollectionAgentLog.log("H-viewportKick", "LoadScheduler.submitViewportKickJob", "pageKick", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"reason\":\"" + reason + "\",\"from\":" + kickFrom //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"count\":" + kickCount + ",\"priority\":" + priority + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // #endregion
                if (priority && display != null && !display.isDisposed())
                {
                    markDirtyViewport();
                    display.asyncExec(() -> {
                        if (!disposed.get() && isShellActiveForLoad())
                            repaintDirtyLogicalRowsInViewport();
                    });
                }
                scheduleSizePass();
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("viewport getVariables: " + e.getMessage()); //$NON-NLS-1$
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        viewportKickJob.setSystem(true);
        if (priority)
            viewportKickJob.setPriority(Job.INTERACTIVE);
        viewportKickJob.schedule();
    }

    /** Перерисовка видимых строк без invalidate и без viewport kick (как EDT RefreshValuesDelegateJob). */
    void repaintBoundTable()
    {
        if (display == null || display.isDisposed() || disposed.get())
            return;
        display.asyncExec(() -> {
            if (disposed.get() || !isShellActiveForLoad())
                return;
            Table table = tableForViewport;
            if (table == null || table.isDisposed())
                return;
            int itemCount = table.getItemCount();
            if (itemCount <= 0)
                return;
            int top = Math.max(0, table.getTopIndex());
            int visible = estimateVisibleRowCount(table);
            int last = Math.min(itemCount - 1, top + visible - 1);
            if (last < top)
                return;
            captureViewport(top, last);
            fireRowsReady(top, last - top + 1);
        });
    }

    /** Сброс кэша ячеек и принудительный SetData для видимых строк таблицы (display-индексы). */
    void refreshBoundTable()
    {
        if (display == null || display.isDisposed() || disposed.get())
            return;
        display.asyncExec(() -> {
            if (disposed.get() || !isShellActiveForLoad())
                return;
            model.invalidateAllCells();
            Table table = tableForViewport;
            if (table == null || table.isDisposed())
                return;
            int itemCount = table.getItemCount();
            if (itemCount <= 0)
                return;
            int top = Math.max(0, table.getTopIndex());
            int visible = estimateVisibleRowCount(table);
            int last = Math.min(itemCount - 1, top + visible - 1);
            if (last < top)
                return;
            captureViewport(top, last);
            fireRowsReady(top, last - top + 1);
        });
    }

    private void markDirtyLogicalRowDebounced(int logicalRow)
    {
        if (logicalRow < 0)
            return;
        synchronized (dirtyRowsLock)
        {
            dirtyLogicalRows.set(logicalRow);
        }
        if (display == null || display.isDisposed() || disposed.get())
            return;
        Runnable schedule = () -> {
            if (display.isDisposed() || disposed.get())
                return;
            if (pendingDirtyDebounce != null)
                display.timerExec(-1, pendingDirtyDebounce);
            pendingDirtyDebounce = () -> {
                pendingDirtyDebounce = null;
                scheduleDebugRefreshDebounced();
            };
            display.timerExec((int) PENDING_DIRTY_DEBOUNCE_MS, pendingDirtyDebounce);
        };
        if (display.getThread() == Thread.currentThread())
            schedule.run();
        else
            display.asyncExec(schedule);
    }

    private void markDirtyForDebugSource(Object source, IBslIndexedValue indexed)
    {
        if (source == indexed || indexed.equals(source))
        {
            if (!initialLoadSettled.get())
                markDirtyViewport();
            return;
        }
        int row = resolveLogicalRowInViewport(source, indexed);
        if (row >= 0)
        {
            synchronized (dirtyRowsLock)
            {
                dirtyLogicalRows.set(row);
            }
            return;
        }
        if (source instanceof IBslValue || source instanceof IBslVariable)
            markDirtyViewport();
    }

    private void markDirtyViewport()
    {
        int from = viewportFirst.get();
        int to = viewportLast.get();
        if (to < from)
            return;
        synchronized (dirtyRowsLock)
        {
            dirtyLogicalRows.set(from, to + 1);
        }
    }

    private int resolveLogicalRowInViewport(Object source, IBslIndexedValue indexed)
    {
        if (source == null || indexed == null)
            return -1;
        int from = viewportFirst.get();
        int to = viewportLast.get();
        int total = model.totalSize;
        if (total > 0)
            to = Math.min(to, total - 1);
        if (to < from)
            return -1;
        try
        {
            for (int row = from; row <= to; row++)
            {
                IBslVariable rowVar = model.getRowVariable(row);
                if (rowVar == null)
                    rowVar = indexed.getVariable(row);
                if (rowVar == null)
                    continue;
                if (source == rowVar || rowVar.equals(source))
                    return row;
                IBslValue rowValue = rowVar.getValue();
                if (source instanceof IBslValue value)
                {
                    if (value == rowValue || value.equals(rowValue))
                        return row;
                    IBslVariable[] props = DebugCollectionPropertyVariables.propertyVariablesForRow(rowVar);
                    if (props != null)
                    {
                        for (IBslVariable prop : props)
                        {
                            if (prop == null)
                                continue;
                            if (source == prop || prop.equals(source))
                                return row;
                            IBslValue propValue = prop.getValue();
                            if (value == propValue || value.equals(propValue))
                                return row;
                        }
                    }
                }
                if (source instanceof IBslVariable var)
                {
                    IBslVariable[] props = DebugCollectionPropertyVariables.propertyVariablesForRow(rowVar);
                    if (props != null)
                    {
                        for (IBslVariable prop : props)
                        {
                            if (prop == null)
                                continue;
                            if (var == prop || var.equals(prop))
                                return row;
                        }
                    }
                }
            }
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("resolveLogicalRow: " + e.getMessage()); //$NON-NLS-1$
        }
        return -1;
    }

    /** @return число перерисованных logical-строк в viewport */
    private int repaintDirtyLogicalRowsInViewport()
    {
        int vpFrom = viewportFirst.get();
        int vpTo = viewportLast.get();
        int total = model.totalSize;
        if (total > 0)
            vpTo = Math.min(vpTo, total - 1);
        if (vpTo < vpFrom || progress == null)
            return 0;
        int[] dirtyRows;
        synchronized (dirtyRowsLock)
        {
            int count = 0;
            for (int row = vpFrom; row <= vpTo; row++)
            {
                if (dirtyLogicalRows.get(row))
                    count++;
            }
            if (count == 0)
                return 0;
            dirtyRows = new int[count];
            int idx = 0;
            for (int row = vpFrom; row <= vpTo; row++)
            {
                if (dirtyLogicalRows.get(row))
                {
                    dirtyRows[idx++] = row;
                    dirtyLogicalRows.clear(row);
                }
            }
        }
        for (int row : dirtyRows)
        {
            model.invalidateLogicalRow(row);
            progress.onRepaintLogicalRow(row);
        }
        return dirtyRows.length;
    }

    private static IDebugTarget debugTargetOf(Object source)
    {
        if (source instanceof IDebugElement element)
            return element.getDebugTarget();
        return null;
    }

    private static boolean isRelevantDebugSource(
        Object source,
        IBslIndexedValue indexed,
        IDebugTarget collectionTarget)
    {
        if (source == indexed || indexed.equals(source))
            return true;
        if (collectionTarget == null || !(source instanceof IDebugElement element))
            return false;
        IDebugTarget sourceTarget = element.getDebugTarget();
        return sourceTarget != null && sourceTarget.equals(collectionTarget);
    }

    private static int estimateVisibleRowCount(Table table)
    {
        if (table == null || table.isDisposed())
            return 24;
        int itemHeight = table.getItemHeight();
        if (itemHeight <= 0)
            return 24;
        return table.getClientArea().height / itemHeight + 2;
    }

    private void refreshViewportRows()
    {
        repaintBoundTable();
    }

    private void scheduleContextJob()
    {
        if (disposed.get() || contextColumnsListener == null)
            return;
        cancelJob(contextJob);
        scheduleContextJobInternal();
    }

    private void scheduleContextJobInternal()
    {
        contextJob = Job.create("Комфорт: колонки коллекции", monitor -> { //$NON-NLS-1$
            IBslVariable[] context = null;
            try
            {
                context = DebugCollectionContextColumnsResolver.resolve(model.indexedValue);
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("contextVariables: " + e.getMessage()); //$NON-NLS-1$
            }
            final IBslVariable[] result = context != null ? context : new IBslVariable[0];
            boolean metadataOnly = DebugCollectionPropertyVariables.isRowMetadataContext(result);
            boolean indexedPlaceholders = DebugCollectionPropertyVariables.isIndexedPlaceholderContext(result);
            if ((result.length == 0 || metadataOnly || indexedPlaceholders) && !disposed.get()
                && !monitor.isCanceled() && contextResolveAttempts.incrementAndGet() <= CONTEXT_RESOLVE_MAX_ATTEMPTS)
            {
                Job followUp = Job.create("Комфорт: колонки коллекции (retry)", m -> { //$NON-NLS-1$
                    scheduleContextJobInternal();
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                });
                followUp.setSystem(true);
                followUp.schedule(200);
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
            if (result.length > 0 && !metadataOnly && !indexedPlaceholders)
                contextResolveAttempts.set(0);
            final IBslVariable[] deliver = metadataOnly || indexedPlaceholders
                ? new IBslVariable[0] : result;
            if (display != null && !display.isDisposed())
            {
                display.asyncExec(() -> {
                    if (!disposed.get())
                        contextColumnsListener.accept(deliver);
                });
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        contextJob.setSystem(true);
        contextJob.schedule();
    }

    private void runFilterScan(DebugCollectionRowFilter filter, org.eclipse.core.runtime.IProgressMonitor monitor, Runnable onDone)
    {
        int total = model.totalSize;
        if (total <= 0)
        {
            asyncDone(onDone);
            return;
        }
        try
        {
            if (model.loadedRowCount < total)
            {
                model.indexedValue.getVariables();
                model.noteCollectionKicked(total);
            }
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("filter getVariables: " + e.getMessage()); //$NON-NLS-1$
            asyncDone(onDone);
            return;
        }

        java.util.BitSet matches = new java.util.BitSet(total);
        for (int row = 0; row < total; row++)
        {
            if (monitor.isCanceled() || disposed.get() || filter.isCancelled())
                break;
            try
            {
                String text = model.rowFilterText(row, filter.isPresentationOnly());
                if (filter.matcher().matches(text))
                    matches.set(row);
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("filter scan: " + e.getMessage()); //$NON-NLS-1$
            }
            if (row % 64 == 63 || row == total - 1)
            {
                fireProgress(row + 1, total, "filter"); //$NON-NLS-1$
                filter.setProgress(row + 1, total, matches);
            }
        }
        if (!filter.isCancelled())
            filter.finishScan(matches);
        asyncDone(onDone);
    }

    private boolean isShellActiveForLoad()
    {
        if (disposed.get())
            return false;
        if (shellMinimized.get())
            return false;
        return shellVisible.get();
    }

    private static boolean isJobBusy(Job job)
    {
        if (job == null)
            return false;
        int state = job.getState();
        return state == Job.RUNNING || state == Job.WAITING || state == Job.SLEEPING;
    }

    private void removeShellStateListener()
    {
        if (shellStateListener == null)
            return;
        Shell shell = shellForPause;
        if (shell != null && !shell.isDisposed())
        {
            shell.removeListener(SWT.Show, shellStateListener);
            shell.removeListener(SWT.Hide, shellStateListener);
            shell.removeListener(SWT.Iconify, shellStateListener);
            shell.removeListener(SWT.Deiconify, shellStateListener);
        }
        shellStateListener = null;
    }

    private void fireProgress(int loaded, int total, String phase)
    {
        if (progress == null || display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (!disposed.get())
                progress.onProgress(loaded, total, phase);
        });
    }

    private void fireRowsReady(int first, int count)
    {
        if (progress == null || display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (!disposed.get())
                progress.onRowsReady(first, count);
        });
    }

    private void asyncDone(Runnable onDone)
    {
        if (onDone == null || display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (!disposed.get())
                onDone.run();
        });
    }

    private static void cancelJob(Job job)
    {
        if (job != null)
            job.cancel();
    }

    /**
     * Схема колонок: union property-имён из строк (как EDT {@code getMutualProperties}) или
     * {@code getContextVariables()} коллекции.
     */
    private static final class DebugCollectionContextColumnsResolver
    {
        private static final int SAMPLE_ROWS = 16;
        private static final int MAX_DIRECT_CONTEXT_COLS = 32;
        private static final int MAX_COLUMN_COUNT = 256;

        private DebugCollectionContextColumnsResolver() {}

        static IBslVariable[] resolve(IBslIndexedValue indexed) throws DebugException
        {
            if (indexed == null)
                return new IBslVariable[0];

            IBslVariable[] mutual = resolveMutualFromIndexed(indexed);
            if (DebugCollectionPropertyVariables.isAcceptableColumnContext(mutual))
            {
                IBslVariable[] chosen = capColumns(mutual);
                DebugCollectionDebug.step("columns.ctx", "mutual=" + mutual.length); //$NON-NLS-1$ //$NON-NLS-2$
                return chosen;
            }

            IBslVariable[] direct = indexed.getContextVariables();
            if (direct == null)
                direct = new IBslVariable[0];

            if (DebugCollectionPropertyVariables.isAcceptableColumnContext(direct)
                && direct.length > 0 && direct.length <= MAX_DIRECT_CONTEXT_COLS)
            {
                DebugCollectionDebug.step("columns.ctx", "direct=" + direct.length); //$NON-NLS-1$ //$NON-NLS-2$
                return direct;
            }

            if (direct.length > MAX_DIRECT_CONTEXT_COLS)
            {
                IBslVariable[] union = capColumns(direct);
                DebugCollectionDebug.step("columns.ctx", //$NON-NLS-1$
                    "direct=" + direct.length + " wide→union=" + union.length); //$NON-NLS-1$ //$NON-NLS-2$
                return union;
            }

            IBslVariable[] rowUnion = resolveRowSampleUnion(indexed);
            if (DebugCollectionPropertyVariables.isAcceptableColumnContext(rowUnion))
            {
                IBslVariable[] chosen = capColumns(rowUnion);
                DebugCollectionDebug.step("columns.ctx", "rowUnion=" + rowUnion.length); //$NON-NLS-1$ //$NON-NLS-2$
                return chosen;
            }

            if (isTabularCollectionType(indexed))
            {
                IBslVariable[] tableSchema = resolveValueTableSchemaColumns(indexed);
                if (DebugCollectionPropertyVariables.isAcceptableColumnContext(tableSchema))
                {
                    DebugCollectionDebug.step("columns.ctx", "tableSchema=" + tableSchema.length); //$NON-NLS-1$ //$NON-NLS-2$
                    return capColumns(tableSchema);
                }
            }

            if (direct.length > 0 && DebugCollectionPropertyVariables.isAcceptableColumnContext(direct))
            {
                DebugCollectionDebug.step("columns.ctx", "directFallback=" + direct.length); //$NON-NLS-1$ //$NON-NLS-2$
                return capColumns(direct);
            }
            return new IBslVariable[0];
        }

        /** Как EDT {@code getMutualProperties}: union имён property по строкам sample. */
        private static IBslVariable[] resolveMutualFromIndexed(IBslIndexedValue indexed) throws DebugException
        {
            int size = indexed.getSize();
            if (size <= 0)
                return new IBslVariable[0];

            int count = Math.min(size, SAMPLE_ROWS);
            IBslVariable[] rows = indexed.getVariables(0, count);
            if (rows == null || rows.length == 0)
                return new IBslVariable[0];

            Set<String> union = new LinkedHashSet<>();
            Map<String, IBslVariable> templates = new LinkedHashMap<>();
            for (IBslVariable row : rows)
            {
                Set<String> rowNames = new LinkedHashSet<>();
                collectPropertyNames(row, rowNames, templates);
                union.addAll(rowNames);
            }
            if (union.isEmpty())
                return new IBslVariable[0];

            List<IBslVariable> result = new ArrayList<>();
            for (String name : union)
            {
                IBslVariable template = templates.get(name);
                if (template != null)
                    result.add(template);
            }
            return result.toArray(new IBslVariable[0]);
        }

        private static IBslVariable[] resolveRowSampleUnion(IBslIndexedValue indexed) throws DebugException
        {
            int size = indexed.getSize();
            if (size <= 0)
                return new IBslVariable[0];

            int count = Math.min(size, SAMPLE_ROWS);
            IBslVariable[] rows = indexed.getVariables(0, count);
            if (rows == null || rows.length == 0)
                return new IBslVariable[0];

            Map<String, IBslVariable> templates = new LinkedHashMap<>();
            for (IBslVariable row : rows)
            {
                if (row == null)
                    continue;
                IBslValue value = row.getValue();
                if (value == null)
                    continue;
                IBslVariable[] props = DebugCollectionPropertyVariables.propertySource(value);
                if (props == null)
                    continue;
                for (IBslVariable prop : props)
                {
                    if (prop == null)
                        continue;
                    String name = prop.getName();
                    if (name == null || name.isBlank())
                        continue;
                    templates.putIfAbsent(name, prop);
                }
            }
            return templates.values().toArray(new IBslVariable[0]);
        }

        private static IBslVariable[] resolveValueTableSchemaColumns(IBslIndexedValue indexed) throws DebugException
        {
            if (!isTabularCollectionType(indexed))
                return new IBslVariable[0];

            IBslVariable columnsVar = findNamedVariable(indexed.getContextVariables(), "Колонки"); //$NON-NLS-1$
            if (columnsVar == null)
                columnsVar = findNamedVariable(indexed.getVariables(), "Колонки"); //$NON-NLS-1$
            if (columnsVar == null)
                return new IBslVariable[0];

            IBslValue columnsValue = columnsVar.getValue();
            if (columnsValue == null)
                return new IBslVariable[0];

            if (columnsValue instanceof IBslIndexedValue columnsIndexed)
            {
                int colSize = columnsIndexed.getSize();
                if (colSize <= 0)
                    return new IBslVariable[0];
                IBslVariable[] defs = columnsIndexed.getVariables();
                if (defs == null || defs.length == 0)
                    return new IBslVariable[0];
                if (defs.length > MAX_COLUMN_COUNT)
                    defs = Arrays.copyOf(defs, MAX_COLUMN_COUNT);
                Map<String, IBslVariable> templates = new LinkedHashMap<>();
                for (IBslVariable def : defs)
                {
                    if (def == null)
                        continue;
                    String name = def.getName();
                    if (name == null || name.isBlank())
                        continue;
                    templates.putIfAbsent(name, def);
                }
                return templates.values().toArray(new IBslVariable[0]);
            }

            IBslVariable[] defs = columnsValue.getVariables();
            if (defs == null || defs.length == 0)
                return new IBslVariable[0];
            Map<String, IBslVariable> templates = new LinkedHashMap<>();
            for (IBslVariable def : defs)
            {
                if (def == null)
                    continue;
                String name = def.getName();
                if (name == null || name.isBlank())
                    continue;
                templates.putIfAbsent(name, def);
            }
            return templates.values().toArray(new IBslVariable[0]);
        }

        private static boolean isTabularCollectionType(IBslIndexedValue indexed)
        {
            if (indexed == null)
                return false;
            String typeName = indexed.getValueTypeName();
            if (typeName == null || typeName.isBlank())
                return false;
            String lower = typeName.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("таблицазначений") //$NON-NLS-1$
                || lower.contains("valuetable") //$NON-NLS-1$
                || lower.contains("деревозначений") //$NON-NLS-1$
                || lower.contains("valuetree"); //$NON-NLS-1$
        }

        private static IBslVariable findNamedVariable(IBslVariable[] variables, String name)
        {
            if (variables == null || variables.length == 0 || name == null || name.isBlank())
                return null;
            for (IBslVariable variable : variables)
            {
                if (variable == null)
                    continue;
                String variableName = variable.getName();
                if (variableName != null && variableName.equalsIgnoreCase(name))
                    return variable;
            }
            return null;
        }

        private static IBslVariable[] capColumns(IBslVariable[] direct)
        {
            if (direct.length <= MAX_COLUMN_COUNT)
                return direct;
            DebugCollectionDebug.step("columns.ctx", //$NON-NLS-1$
                "capped " + direct.length + "→" + MAX_COLUMN_COUNT); //$NON-NLS-1$ //$NON-NLS-2$
            return Arrays.copyOf(direct, MAX_COLUMN_COUNT);
        }

        private static void collectPropertyNames(
            IBslVariable row,
            Set<String> names,
            Map<String, IBslVariable> templates) throws DebugException
        {
            if (row == null)
                return;
            IBslValue value = row.getValue();
            if (value == null)
                return;

            IBslVariable[] props = DebugCollectionPropertyVariables.propertySource(value);
            if (props == null)
                return;
            for (IBslVariable prop : props)
            {
                if (prop == null)
                    continue;
                String name = prop.getName();
                if (name == null || name.isBlank())
                    continue;
                if (names.add(name))
                    templates.putIfAbsent(name, prop);
            }
        }
    }
}
