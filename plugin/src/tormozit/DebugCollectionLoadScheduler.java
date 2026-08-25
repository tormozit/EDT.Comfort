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
    private static final long SIZE_PASS_DEBOUNCE_MS = 120L;
    private static final long SIZE_PASS_RETRY_DEBOUNCE_MS = 180L;
    private static final long SIZE_PASS_RETRY_MAX_DELAY_MS = 5000L;
    private static final long SIZE_REPAINT_DEBOUNCE_MS = 80L;
    private static final int DEBUG_DETAIL_STATE = 256;
    private static final int DEBUG_DETAIL_CONTENT = 512;
    private static final int OVERSCAN = 8;
    /** Сколько раз повторить resolve после первой неудачи (пусто / metadata / placeholders). */
    private static final int CONTEXT_RESOLVE_MAX_ATTEMPTS = 10;
    /** Пауза между попытками; 10×200 мс ≈ 2 с максимум ожидания схемы. */
    private static final long CONTEXT_RESOLVE_RETRY_DELAY_MS = 200L;
    /** Временный лог EDT 2026: схема колонок не приходит. */
    static final String COLUMNS_TEMP_LOG = "collection-columns"; //$NON-NLS-1$

    static String describeContextVars(IBslVariable[] vars)
    {
        return DebugCollectionContextColumnsResolver.describeVars(vars);
    }

    static void logColumns(String text)
    {
        Global.tempLog(COLUMNS_TEMP_LOG, text);
    }

    static void logColumnsException(String context, Throwable t)
    {
        Global.tempLogException(COLUMNS_TEMP_LOG, context, t);
    }

    interface ProgressListener
    {
        void onProgress(int loaded, int total, String phase);

        void onRowsReady(int displayFirst, int displayCount);

        /** Точечная перерисовка одной logical-строки (как EDT viewer.update). */
        void onRepaintLogicalRow(int logicalRow);
    }

    /** display-индекс таблицы → logical-строка модели (фильтр / split). */
    interface LogicalRowMapper
    {
        int toLogical(int displayIndex);
    }

    private final DebugCollectionTableModel model;
    private final Display display;
    private final ProgressListener progress;
    private final Consumer<IBslVariable[]> contextColumnsListener;
    private Table tableForViewport;
    private LogicalRowMapper logicalRowMapper;
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
    private final java.util.concurrent.atomic.AtomicLong contextResolveStartedAtMs =
        new java.util.concurrent.atomic.AtomicLong(0L);
    /** Уже отдали provisional-схему `[0]…[N]` — повторно не шлём, ждём реальные имена. */
    private final java.util.concurrent.atomic.AtomicBoolean provisionalColumnsDelivered =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger sizeRowFrom =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizeRowTo =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizeRowCoreFrom =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizeRowCoreTo =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizeColFrom =
        new java.util.concurrent.atomic.AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicInteger sizeColTo =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizePassGeneration =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger viewportGeneration =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean sizePassOverscanPhase =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean sizeRetryPending =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean firstSizePassCompleted =
        new java.util.concurrent.atomic.AtomicBoolean();
    // Счётчик подряд идущих "пустых" retry (needsRetry=true, но без реального прогресса) —
    // без него scheduleSizeRetryDebounced() долбит ровно каждые 180мс НАВСЕГДА, даже когда
    // застрявшие ячейки провисят там же (contentLoaded=false, вне viewport) и в принципе не могут
    // продвинуться без нового скролла. Видели в логе: 68 подряд retry по ~1мс каждый за 13 секунд.
    private final java.util.concurrent.atomic.AtomicInteger sizeRetryNoProgressStreak =
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
    private Runnable sizePassDebounce;
    private Runnable sizeRetryDebounce;
    private Runnable sizeRowsReadyDebounce;
    private int sizeRepaintRowFrom = -1;
    private int sizeRepaintRowTo = -1;

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
        contextResolveStartedAtMs.set(0L);
        provisionalColumnsDelivered.set(false);
        model.setDirtyRowHandler(this::markDirtyLogicalRowDebounced);
    }

    void bindTable(Table table)
    {
        tableForViewport = table;
    }

    void bindLogicalRowMapper(LogicalRowMapper mapper)
    {
        logicalRowMapper = mapper;
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
        initialLoadSettled.set(false);
        firstSizePassCompleted.set(false);
        sizePassOverscanPhase.set(false);
        sizeRetryPending.set(false);
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
        initialLoadSettled.set(true);
        firstSizePassCompleted.set(true);
        sizePassOverscanPhase.set(false);
        sizeRetryPending.set(false);
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
        sizeRowCoreFrom.set(logicalFirst);
        sizeRowCoreTo.set(logicalLast);
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

    private void captureSizeViewportFromBoundTable()
    {
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
        LogicalRowMapper mapper = logicalRowMapper;
        int logicalFirst = mapper != null ? mapper.toLogical(top) : top;
        int logicalLast = mapper != null ? mapper.toLogical(last) : last;
        if (logicalFirst < 0 || logicalLast < logicalFirst)
            return;
        captureSizeViewport(logicalFirst, logicalLast, table);
    }

    void scheduleSizePassDebounced()
    {
        scheduleSizePassDebounced(SIZE_PASS_DEBOUNCE_MS);
    }

    void scheduleSizePassDebounced(long delayMs)
    {
        if (disposed.get() || !isShellActiveForLoad())
            return;
        if (model.totalSize == 0)
            return;
        if (display == null || display.isDisposed())
            return;
        Runnable schedule = () -> {
            if (display.isDisposed() || disposed.get())
                return;
            if (sizePassDebounce != null)
                display.timerExec(-1, sizePassDebounce);
            sizePassDebounce = () -> {
                sizePassDebounce = null;
                if (disposed.get() || !isShellActiveForLoad())
                    return;
                if (isJobBusy(viewportKickJob))
                {
                    scheduleSizePassDebounced(SIZE_PASS_DEBOUNCE_MS);
                    return;
                }
                captureSizeViewportFromBoundTable();
                runSizePass(true, "kick"); //$NON-NLS-1$
            };
            display.timerExec((int) delayMs, sizePassDebounce);
        };
        if (display.getThread() == Thread.currentThread())
            schedule.run();
        else
            display.asyncExec(schedule);
    }

    void notifyCellContentReady(int logicalRow, int visibleCol)
    {
        if (disposed.get() || !isShellActiveForLoad() || logicalRow < 0 || visibleCol < 0)
            return;
        if (!firstSizePassCompleted.get())
            return;
        if (DebugCollectionSizeResolver.isSizePassSkippedColumn(model, visibleCol))
            return;
        DebugCollectionTableModel.CellData data = model.cellData(logicalRow, visibleCol);
        synchronized (data)
        {
            if (!data.contentLoaded)
                return;
            if (data.sizeState != DebugCollectionTableModel.SizeState.UNKNOWN
                && data.sizeState != DebugCollectionTableModel.SizeState.PENDING)
                return;
        }
        scheduleSizeRetryDebounced();
    }

    private void scheduleSizeRetryDebounced()
    {
        scheduleSizeRetryDebounced(SIZE_PASS_RETRY_DEBOUNCE_MS);
    }

    private void scheduleSizeRetryDebounced(long delayMs)
    {
        if (disposed.get() || !isShellActiveForLoad())
            return;
        if (model.totalSize == 0)
            return;
        if (display == null || display.isDisposed())
            return;
        Runnable schedule = () -> {
            if (display.isDisposed() || disposed.get())
                return;
            if (sizeRetryDebounce != null)
                display.timerExec(-1, sizeRetryDebounce);
            sizeRetryDebounce = () -> {
                sizeRetryDebounce = null;
                if (disposed.get() || !isShellActiveForLoad())
                    return;
                runSizePass(false, "retry"); //$NON-NLS-1$
            };
            display.timerExec((int) delayMs, sizeRetryDebounce);
        };
        if (display.getThread() == Thread.currentThread())
            schedule.run();
        else
            display.asyncExec(schedule);
    }

    private void runSizePass(boolean viewportChanged, String reason)
    {
        if (display == null || display.isDisposed())
            return;
        if (viewportChanged)
        {
            sizePassOverscanPhase.set(false);
            sizeRetryNoProgressStreak.set(0);
            int gen = sizePassGeneration.incrementAndGet();
            viewportGeneration.incrementAndGet();
            DebugCollectionSizeResolver.cancelPass(gen);
            sizeRetryPending.set(false);
            display.asyncExec(() -> {
                if (disposed.get() || gen != sizePassGeneration.get())
                    return;
                executeSizePass(gen, reason, true);
            });
        }
        else
        {
            if (DebugCollectionSizeResolver.isPassBusy())
            {
                sizeRetryPending.set(true);
                return;
            }
            int gen = sizePassGeneration.get();
            if (gen <= 0)
                gen = sizePassGeneration.incrementAndGet();
            final int passGen = gen;
            display.asyncExec(() -> {
                if (disposed.get() || passGen != sizePassGeneration.get())
                    return;
                executeSizePass(passGen, reason, false);
            });
        }
    }

    private void executeSizePass(int generation, String reason, boolean cancelPrevious)
    {
        int coreFrom = sizeRowCoreFrom.get();
        int coreTo = sizeRowCoreTo.get();
        int overscanFrom = viewportFirst.get();
        int overscanTo = viewportLast.get();
        int colFrom = sizeColFrom.get();
        int colTo = sizeColTo.get();
        if (coreFrom < 0 || coreTo < coreFrom)
        {
            captureSizeViewportFromBoundTable();
            coreFrom = sizeRowCoreFrom.get();
            coreTo = sizeRowCoreTo.get();
        }
        if (colTo < colFrom)
        {
            colFrom = 1;
            colTo = Math.max(1, model.columns.columnCount() - 1);
        }
        if (coreFrom < 0 || coreTo < coreFrom || colTo < colFrom)
            return;
        int total = model.totalSize;
        if (total > 0)
        {
            coreTo = Math.min(coreTo, total - 1);
            overscanTo = Math.min(overscanTo, total - 1);
        }
        if (overscanFrom < 0)
            overscanFrom = coreFrom;
        if (overscanTo < coreTo)
            overscanTo = coreTo;
        final boolean includeOverscan = sizePassOverscanPhase.get();
        final int readyFrom = coreFrom;
        final int readyCount = coreTo - coreFrom + 1;
        final int viewportGen = viewportGeneration.get();
        DebugCollectionDebug.step("load", //$NON-NLS-1$
            "size core=" + coreFrom + ".." + coreTo //$NON-NLS-1$ //$NON-NLS-2$
                + " overscan=" + overscanFrom + ".." + overscanTo //$NON-NLS-1$ //$NON-NLS-2$
                + " cols=" + colFrom + ".." + colTo //$NON-NLS-1$ //$NON-NLS-2$
                + " phase=" + (includeOverscan ? "overscan" : "core")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DebugCollectionSizeResolver.schedulePass(
            model,
            coreFrom,
            coreTo,
            overscanFrom,
            overscanTo,
            colFrom,
            colTo,
            display,
            generation,
            cancelPrevious,
            includeOverscan,
            reason,
            viewportGen,
            logicalRow -> {
                if (progress != null && !disposed.get())
                    progress.onRepaintLogicalRow(logicalRow);
            },
            () -> {
                sizeRetryNoProgressStreak.set(0); // реальный прогресс — сбрасываем бэкофф
                fireRowsReadyDebounced(readyFrom, readyCount);
            },
            needsRetry -> {
                if (disposed.get() || !isShellActiveForLoad())
                    return;
                firstSizePassCompleted.set(true);
                if (needsRetry)
                {
                    int streak = sizeRetryNoProgressStreak.incrementAndGet();
                    long delay = Math.min(SIZE_PASS_RETRY_MAX_DELAY_MS,
                        SIZE_PASS_RETRY_DEBOUNCE_MS << Math.min(streak, 8));
                    scheduleSizeRetryDebounced(delay);
                }
                else if (!includeOverscan)
                {
                    sizePassOverscanPhase.set(true);
                    scheduleSizeRetryDebounced();
                }
                if (sizeRetryPending.getAndSet(false))
                    scheduleSizeRetryDebounced();
            });
    }

    private void fireRowsReadyDebounced(int from, int count)
    {
        if (progress == null || display == null || display.isDisposed())
            return;
        int to = from + Math.max(0, count - 1);
        synchronized (this)
        {
            if (sizeRepaintRowFrom < 0)
            {
                sizeRepaintRowFrom = from;
                sizeRepaintRowTo = to;
            }
            else
            {
                sizeRepaintRowFrom = Math.min(sizeRepaintRowFrom, from);
                sizeRepaintRowTo = Math.max(sizeRepaintRowTo, to);
            }
        }
        Runnable schedule = () -> {
            if (display.isDisposed() || disposed.get())
                return;
            if (sizeRowsReadyDebounce != null)
                display.timerExec(-1, sizeRowsReadyDebounce);
            sizeRowsReadyDebounce = () -> {
                sizeRowsReadyDebounce = null;
                int repaintFrom;
                int repaintTo;
                synchronized (DebugCollectionLoadScheduler.this)
                {
                    repaintFrom = sizeRepaintRowFrom;
                    repaintTo = sizeRepaintRowTo;
                    sizeRepaintRowFrom = -1;
                    sizeRepaintRowTo = -1;
                }
                if (repaintFrom < 0 || disposed.get())
                    return;
                fireRowsReady(repaintFrom, repaintTo - repaintFrom + 1);
            };
            display.timerExec((int) SIZE_REPAINT_DEBOUNCE_MS, sizeRowsReadyDebounce);
        };
        if (display.getThread() == Thread.currentThread())
            schedule.run();
        else
            display.asyncExec(schedule);
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
        if (!model.isRowsLoaded())
        {
            scheduleCollectionRekick("schema"); //$NON-NLS-1$
            refreshBoundTable();
        }
        // Если строки уже загружены — UI сам делает refreshRowsAfterSchemaChange
        // (setItemCount 0→N + layout). Не вызывать repaintBoundTable/fireRowsReady:
        // отложенный clear через 50 мс снова стирает видимые ячейки index-таблицы
        // (колонка «Индекс» остаётся пустой до скролла).
    }

    void cancelAll()
    {
        disposed.set(true);
        shellVisible.set(false);
        sizePassGeneration.incrementAndGet();
        if (display != null && !display.isDisposed())
        {
            if (sizePassDebounce != null)
                display.timerExec(-1, sizePassDebounce);
            if (sizeRetryDebounce != null)
                display.timerExec(-1, sizeRetryDebounce);
            if (sizeRowsReadyDebounce != null)
                display.timerExec(-1, sizeRowsReadyDebounce);
            sizePassDebounce = null;
            sizeRetryDebounce = null;
            sizeRowsReadyDebounce = null;
        }
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
                    logColumns( "variablesJob size=0 skip context reason=" + reason); //$NON-NLS-1$
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
                    logRowVariableSample();
                }
                fireProgress(model.loadedRowCount, size, "rows"); //$NON-NLS-1$
                initialLoadSettled.set(true);
                DebugCollectionDebug.step("load", reason + " rows=" + size); //$NON-NLS-1$ //$NON-NLS-2$
                if (scheduleContextAfter)
                {
                    logColumns("variablesJob scheduleContext reason=" + reason //$NON-NLS-1$
                        + " size=" + size); //$NON-NLS-1$
                    try
                    {
                        scheduleContextJob();
                    }
                    catch (Throwable t)
                    {
                        logColumnsException("variablesJob scheduleContext Throwable", t); //$NON-NLS-1$
                    }
                }
                requestViewportPriority(viewportFirst.get(), viewportLast.get());
                repaintBoundTable();
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("getVariables: " + e.getMessage()); //$NON-NLS-1$
                logColumnsException( "variablesJob DebugException reason=" + reason, e); //$NON-NLS-1$
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
            IBslVariable variable = model.getRowVariable(row);
            String name = variable != null ? variable.getName() : "null"; //$NON-NLS-1$
            if (sb.length() > 0)
                sb.append(','); //$NON-NLS-1$
            sb.append(" i=").append(row).append(" name=").append(name); //$NON-NLS-1$ //$NON-NLS-2$
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
                debugEventCoalesceCount.getAndSet(0);
                int dirtyCount = repaintDirtyLogicalRowsInViewport();
                if (dirtyCount <= 0)
                    return;
                DebugCollectionDebug.step("load", "debugRefresh"); //$NON-NLS-1$ //$NON-NLS-2$
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
                if (priority && display != null && !display.isDisposed())
                {
                    markDirtyViewport();
                    display.asyncExec(() -> {
                        if (!disposed.get() && isShellActiveForLoad() && firstSizePassCompleted.get())
                            repaintDirtyLogicalRowsInViewport();
                    });
                }
                if (display != null && !display.isDisposed())
                {
                    display.asyncExec(() -> {
                        if (disposed.get() || !isShellActiveForLoad())
                            return;
                        captureSizeViewportFromBoundTable();
                        scheduleSizePassDebounced();
                    });
                }
                else
                    scheduleSizePassDebounced();
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
        int from = sizeRowCoreFrom.get();
        int to = sizeRowCoreTo.get();
        if (from < 0 || to < from)
        {
            from = viewportFirst.get();
            to = viewportLast.get();
        }
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
        if (!firstSizePassCompleted.get())
            return 0;
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
            // Где бы даже ни пришла причина dirty (реальный debug CHANGE или просто повторный
            // вход в viewport после скролла через markDirtyViewport/submitViewportKickJob), не трогаем
            // уже готовые размеры (READY/NA) — иначе именно этот путь (а не только
            // Window.captureViewportRange) сводил на нет защиту от мигания при прокрутке: submitViewportKickJob
            // с priority=true сам вызывает markDirtyViewport() + этот метод сразу после того, как
            // captureViewportRange уже аккуратно почистил кэш щадящим вариантом.
            model.invalidateLogicalRowPreservingReadySizes(row);
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
        logColumns("scheduleContext enter disposed=" + disposed.get() //$NON-NLS-1$
            + " listener=" + (contextColumnsListener != null) //$NON-NLS-1$
            + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
        try
        {
            if (disposed.get() || contextColumnsListener == null)
            {
                logColumns("scheduleContext skip"); //$NON-NLS-1$
                return;
            }
            cancelJob(contextJob);
            contextResolveAttempts.set(0);
            contextResolveStartedAtMs.set(System.currentTimeMillis());
            provisionalColumnsDelivered.set(false);
            logColumns("scheduleContext beforeInternal size=" + model.totalSize); //$NON-NLS-1$
            scheduleContextJobInternal();
            logColumns("scheduleContext afterInternal"); //$NON-NLS-1$
        }
        catch (Throwable t)
        {
            logColumnsException("scheduleContext Throwable", t); //$NON-NLS-1$
        }
    }

    private void scheduleContextJobInternal()
    {
        contextJob = Job.create("Комфорт: колонки коллекции", monitor -> { //$NON-NLS-1$
            logColumns("job.run enter attempt=" + contextResolveAttempts.get() //$NON-NLS-1$
                + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
            IBslVariable[] result = new IBslVariable[0];
            try
            {
            long startedAt = contextResolveStartedAtMs.get();
            long elapsedMs = startedAt > 0 ? System.currentTimeMillis() - startedAt : 0L;
            int attempt = contextResolveAttempts.get();
            IBslVariable[] context = null;
            try
            {
                logColumns("job.resolve before"); //$NON-NLS-1$
                context = DebugCollectionContextColumnsResolver.resolve(model.indexedValue);
                logColumns("job.resolve after n=" + (context == null ? -1 : context.length)); //$NON-NLS-1$
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("contextVariables: " + e.getMessage()); //$NON-NLS-1$
                logColumnsException("job.resolve DebugException", e); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                logColumnsException("job.resolve RuntimeException", e); //$NON-NLS-1$
            }
            result = context != null ? context : new IBslVariable[0];
            logColumns("job.beforeClassify n=" + result.length); //$NON-NLS-1$
            boolean metadataOnly = DebugCollectionPropertyVariables.isRowMetadataContext(result);
            boolean indexedPlaceholders = DebugCollectionPropertyVariables.isIndexedPlaceholderContext(result);
            logColumns("job.beforeLabels meta=" + metadataOnly + " ph=" + indexedPlaceholders); //$NON-NLS-1$ //$NON-NLS-2$
            boolean labelsReady = indexedPlaceholders
                && DebugCollectionPropertyVariables.hasResolvedColumnLabels(result);
            logColumns("job.afterLabels " + labelsReady); //$NON-NLS-1$
            boolean tabular = DebugCollectionContextColumnsResolver.isTabularCollectionType(model.indexedValue);
            boolean provisional = indexedPlaceholders && !labelsReady && tabular && result.length > 0;
            boolean acceptable = result.length > 0 && !metadataOnly
                && (!indexedPlaceholders || labelsReady);
            elapsedMs = startedAt > 0 ? System.currentTimeMillis() - startedAt : 0L;

            boolean canRetry = !disposed.get() && !monitor.isCanceled()
                && elapsedMs < 2000L
                && contextResolveAttempts.get() < CONTEXT_RESOLVE_MAX_ATTEMPTS;

            logColumns("job.flags attempt=" + attempt //$NON-NLS-1$
                + " elapsedMs=" + elapsedMs //$NON-NLS-1$
                + " n=" + result.length //$NON-NLS-1$
                + " meta=" + metadataOnly //$NON-NLS-1$
                + " ph=" + indexedPlaceholders //$NON-NLS-1$
                + " labels=" + labelsReady //$NON-NLS-1$
                + " tabular=" + tabular //$NON-NLS-1$
                + " provisional=" + provisional //$NON-NLS-1$
                + " acceptable=" + acceptable //$NON-NLS-1$
                + " canRetry=" + canRetry //$NON-NLS-1$
                + " deliveredProv=" + provisionalColumnsDelivered.get()); //$NON-NLS-1$
            logColumns("job.flags vars " + DebugCollectionContextColumnsResolver.describeVars(result)); //$NON-NLS-1$

            if (acceptable)
            {
                contextResolveAttempts.set(0);
                logColumns( "job.decision deliver-acceptable n=" + result.length); //$NON-NLS-1$
                deliverContextColumns(result);
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }

            if (provisional && provisionalColumnsDelivered.compareAndSet(false, true))
            {
                logColumns( "job.decision deliver-provisional n=" + result.length //$NON-NLS-1$
                    + " retry=" + canRetry); //$NON-NLS-1$
                deliverContextColumns(result);
                if (canRetry)
                {
                    contextResolveAttempts.incrementAndGet();
                    scheduleContextRefineRetry();
                }
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }

            if ((result.length == 0 || metadataOnly || indexedPlaceholders) && canRetry)
            {
                int next = contextResolveAttempts.incrementAndGet();
                logColumns( "job.decision retry next=" + next //$NON-NLS-1$
                    + " n=" + result.length); //$NON-NLS-1$
                scheduleContextRefineRetry();
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }

            if (!provisionalColumnsDelivered.get())
            {
                final IBslVariable[] deliver = metadataOnly || indexedPlaceholders
                    ? new IBslVariable[0] : result;
                logColumns( "job.decision deliver-final n=" + deliver.length //$NON-NLS-1$
                    + " stripped=" + (deliver.length != result.length)); //$NON-NLS-1$
                deliverContextColumns(deliver);
            }
            else
                logColumns("job.decision keep-provisional n=" + result.length); //$NON-NLS-1$
            return org.eclipse.core.runtime.Status.OK_STATUS;
            }
            catch (Throwable t)
            {
                logColumnsException("job.run Throwable", t); //$NON-NLS-1$
                if (result.length > 0)
                {
                    logColumns("job.run deliver-after-error n=" + result.length); //$NON-NLS-1$
                    deliverContextColumns(result);
                }
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
        });
        contextJob.setSystem(true);
        logColumns("job.schedule before state=" + contextJob.getState()); //$NON-NLS-1$
        contextJob.schedule();
        logColumns("job.schedule after state=" + contextJob.getState()); //$NON-NLS-1$
    }

    private void scheduleContextRefineRetry()
    {
        Job followUp = Job.create("Комфорт: колонки коллекции (retry)", m -> { //$NON-NLS-1$
            scheduleContextJobInternal();
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        followUp.setSystem(true);
        followUp.schedule(CONTEXT_RESOLVE_RETRY_DELAY_MS);
    }

    private void deliverContextColumns(IBslVariable[] deliver)
    {
        final IBslVariable[] payload = deliver != null ? deliver : new IBslVariable[0];
        logColumns("deliver n=" + payload.length //$NON-NLS-1$
            + " displayDisposed=" + (display == null || display.isDisposed())); //$NON-NLS-1$
        logColumns("deliver vars " + DebugCollectionContextColumnsResolver.describeVars(payload)); //$NON-NLS-1$
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(() -> {
                if (!disposed.get())
                    contextColumnsListener.accept(payload);
                else
                    logColumns( "deliver skipped disposed"); //$NON-NLS-1$
            });
        }
        else
            logColumns( "deliver skipped no display"); //$NON-NLS-1$
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

        SmartMatcher matcher = filter.matcher();
        boolean hasSubstring = !matcher.isEmpty;
        boolean hasColumnFilters = filter.hasColumnFilters();
        java.util.BitSet matches = new java.util.BitSet(total);
        for (int row = 0; row < total; row++)
        {
            if (monitor.isCanceled() || disposed.get() || filter.isCancelled())
                break;
            try
            {
                boolean ok = true;
                if (hasSubstring)
                {
                    String text = model.rowFilterText(row, filter.isPresentationOnly());
                    ok = matcher.matches(text);
                }
                if (ok && hasColumnFilters)
                {
                    final int logicalRow = row;
                    ok = filter.columnFiltersMatch(col -> model.getCellDisplayText(logicalRow, col));
                }
                if (ok)
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
            log("resolve.enter cls=" + (indexed == null ? "null" : indexed.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
            if (indexed == null)
            {
                log("resolve.end empty indexed=null"); //$NON-NLS-1$
                return new IBslVariable[0];
            }

            log("resolve.beforeTypeName"); //$NON-NLS-1$
            boolean tabular = isTabularCollectionType(indexed);
            log("resolve.tabular=" + tabular); //$NON-NLS-1$
            log("resolve.beforeSnapshot"); //$NON-NLS-1$
            logCollectionSnapshot(indexed);
            log("resolve.beforeFirstRows"); //$NON-NLS-1$
            logFirstRows(indexed);

            if (tabular)
            {
                IBslVariable[] tableSchema = resolveValueTableSchemaColumns(indexed);
                log("resolve.tableSchema " + classify(tableSchema) + " " + describeVars(tableSchema)); //$NON-NLS-1$ //$NON-NLS-2$
                if (tableSchema != null && tableSchema.length > 0
                    && !DebugCollectionPropertyVariables.isRowMetadataContext(tableSchema))
                {
                    IBslVariable[] chosen = capColumns(tableSchema);
                    DebugCollectionDebug.step("columns.ctx", "tableSchema=" + tableSchema.length //$NON-NLS-1$ //$NON-NLS-2$
                        + (DebugCollectionPropertyVariables.isIndexedPlaceholderContext(tableSchema)
                            ? " provisional" : "")); //$NON-NLS-1$ //$NON-NLS-2$
                    log("resolve.choose tableSchema n=" + chosen.length //$NON-NLS-1$
                        + (DebugCollectionPropertyVariables.isIndexedPlaceholderContext(tableSchema)
                            ? " provisional" : "")); //$NON-NLS-1$ //$NON-NLS-2$
                    return chosen;
                }
                log("resolve.tableSchema rejected"); //$NON-NLS-1$
            }

            IBslVariable[] mutual = resolveMutualFromIndexed(indexed, tabular);
            log("resolve.mutual " + classify(mutual) + " " + describeVars(mutual)); //$NON-NLS-1$ //$NON-NLS-2$
            if (DebugCollectionPropertyVariables.isAcceptableColumnContext(mutual))
            {
                IBslVariable[] chosen = capColumns(mutual);
                DebugCollectionDebug.step("columns.ctx", "mutual=" + mutual.length); //$NON-NLS-1$ //$NON-NLS-2$
                log("resolve.choose mutual n=" + chosen.length); //$NON-NLS-1$
                return chosen;
            }

            IBslVariable[] direct = indexed.getContextVariables();
            if (direct == null)
                direct = new IBslVariable[0];
            boolean itemsAsColumns = looksLikeCollectionItems(indexed, direct);
            log("resolve.direct " + classify(direct) + " " + describeVars(direct) //$NON-NLS-1$ //$NON-NLS-2$
                + " itemsAsColumns=" + itemsAsColumns); //$NON-NLS-1$

            if (!itemsAsColumns
                && DebugCollectionPropertyVariables.isAcceptableColumnContext(direct)
                && direct.length > 0 && direct.length <= MAX_DIRECT_CONTEXT_COLS)
            {
                DebugCollectionDebug.step("columns.ctx", "direct=" + direct.length); //$NON-NLS-1$ //$NON-NLS-2$
                log("resolve.choose direct n=" + direct.length); //$NON-NLS-1$
                return direct;
            }
            if (itemsAsColumns)
                log("resolve.skip direct collection-items n=" + direct.length); //$NON-NLS-1$
            else if (direct.length > MAX_DIRECT_CONTEXT_COLS)
                log("resolve.skip directWide n=" + direct.length); //$NON-NLS-1$

            IBslVariable[] rowUnion = resolveRowSampleUnion(indexed);
            log("resolve.rowUnion " + classify(rowUnion) + " " + describeVars(rowUnion)); //$NON-NLS-1$ //$NON-NLS-2$
            if (DebugCollectionPropertyVariables.isAcceptableColumnContext(rowUnion))
            {
                IBslVariable[] chosen = capColumns(rowUnion);
                DebugCollectionDebug.step("columns.ctx", "rowUnion=" + rowUnion.length); //$NON-NLS-1$ //$NON-NLS-2$
                log("resolve.choose rowUnion n=" + chosen.length); //$NON-NLS-1$
                return chosen;
            }

            if (!itemsAsColumns && direct.length > 0
                && DebugCollectionPropertyVariables.isAcceptableColumnContext(direct))
            {
                DebugCollectionDebug.step("columns.ctx", "directFallback=" + direct.length); //$NON-NLS-1$ //$NON-NLS-2$
                log("resolve.choose directFallback n=" + direct.length); //$NON-NLS-1$
                return capColumns(direct);
            }
            log("resolve.end empty"); //$NON-NLS-1$
            return new IBslVariable[0];
        }

        /** Как EDT {@code getMutualProperties}: union имён property по строкам sample. */
        private static IBslVariable[] resolveMutualFromIndexed(IBslIndexedValue indexed, boolean tabular)
            throws DebugException
        {
            int size = indexed.getSize();
            if (size <= 0)
            {
                log("mutual skip size=" + size); //$NON-NLS-1$
                return new IBslVariable[0];
            }

            int count = Math.min(size, SAMPLE_ROWS);
            IBslVariable[] rows = sampleRows(indexed, count);
            log("mutual.sample size=" + size + " count=" + count //$NON-NLS-1$ //$NON-NLS-2$
                + " rows=" + (rows == null ? "null" : Integer.toString(rows.length))); //$NON-NLS-1$ //$NON-NLS-2$
            if (rows == null || rows.length == 0)
                return new IBslVariable[0];

            Set<String> union = new LinkedHashSet<>();
            Map<String, IBslVariable> templates = new LinkedHashMap<>();
            for (int i = 0; i < rows.length; i++)
            {
                IBslVariable row = rows[i];
                if (row == null)
                {
                    log("mutual.row[" + i + "] null"); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                Set<String> rowNames = new LinkedHashSet<>();
                collectPropertyNames(row, rowNames, templates);
                log("mutual.row[" + i + "] name=" + row.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + " props=" + rowNames); //$NON-NLS-1$
                union.addAll(rowNames);
            }
            if (union.isEmpty() && !tabular)
                kickFirstRowProperties(rows, union, templates);
            log("mutual.union n=" + union.size() + " names=" + union); //$NON-NLS-1$ //$NON-NLS-2$
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

        private static void kickFirstRowProperties(
            IBslVariable[] rows,
            Set<String> union,
            Map<String, IBslVariable> templates) throws DebugException
        {
            for (int i = 0; i < rows.length; i++)
            {
                IBslVariable row = rows[i];
                if (row == null)
                    continue;
                IBslValue value = row.getValue();
                if (value == null || value.isPending())
                    continue;
                if (!value.isEvaluated())
                {
                    BslValueEvaluate.ensureEvaluated(value);
                    log("mutual.kick[" + i + "] name=" + row.getName() //$NON-NLS-1$ //$NON-NLS-2$
                        + " ev=" + value.isEvaluated() + " pend=" + value.isPending()); //$NON-NLS-1$ //$NON-NLS-2$
                }
                Set<String> rowNames = new LinkedHashSet<>();
                collectPropertyNames(row, rowNames, templates);
                log("mutual.kick[" + i + "] props=" + rowNames); //$NON-NLS-1$ //$NON-NLS-2$
                union.addAll(rowNames);
                return;
            }
        }

        private static IBslVariable[] sampleRows(IBslIndexedValue indexed, int count) throws DebugException
        {
            IBslVariable[] rows = indexed.getVariables(0, count);
            if (rows == null || rows.length <= count)
                return rows;
            log("sample.trunc requested=" + count + " got=" + rows.length); //$NON-NLS-1$ //$NON-NLS-2$
            return Arrays.copyOf(rows, count);
        }

        /** Context коллекции совпадает с её элементами — это не схема колонок. */
        private static boolean looksLikeCollectionItems(IBslIndexedValue indexed, IBslVariable[] context)
        {
            if (context == null || context.length == 0)
                return false;
            if (DebugCollectionPropertyVariables.isRowMetadataContext(context))
                return false;
            try
            {
                int size = indexed.getSize();
                return size > 0 && context.length == size;
            }
            catch (DebugException e)
            {
                return false;
            }
        }

        private static IBslVariable[] resolveRowSampleUnion(IBslIndexedValue indexed) throws DebugException
        {
            int size = indexed.getSize();
            if (size <= 0)
            {
                log("rowUnion skip size=" + size); //$NON-NLS-1$
                return new IBslVariable[0];
            }

            int count = Math.min(size, SAMPLE_ROWS);
            IBslVariable[] rows = sampleRows(indexed, count);
            log("rowUnion.sample size=" + size + " count=" + count //$NON-NLS-1$ //$NON-NLS-2$
                + " rows=" + (rows == null ? "null" : Integer.toString(rows.length))); //$NON-NLS-1$ //$NON-NLS-2$
            if (rows == null || rows.length == 0)
                return new IBslVariable[0];

            Map<String, IBslVariable> templates = new LinkedHashMap<>();
            for (int i = 0; i < rows.length; i++)
            {
                IBslVariable row = rows[i];
                if (row == null)
                    continue;
                IBslValue value = row.getValue();
                if (value == null)
                {
                    log("rowUnion.row[" + i + "] value=null"); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                IBslVariable[] props = DebugCollectionPropertyVariables.propertySource(value);
                log("rowUnion.row[" + i + "] " + classify(props) + " " + describeVars(props)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
            log("rowUnion.templates n=" + templates.size() + " names=" + templates.keySet()); //$NON-NLS-1$ //$NON-NLS-2$
            return templates.values().toArray(new IBslVariable[0]);
        }

        private static IBslVariable[] resolveValueTableSchemaColumns(IBslIndexedValue indexed) throws DebugException
        {
            log("schema.start " + describeIndexed(indexed)); //$NON-NLS-1$
            if (!isTabularCollectionType(indexed))
            {
                log("schema.skip not tabular"); //$NON-NLS-1$
                return new IBslVariable[0];
            }

            IBslVariable[] ctxVars = indexed.getContextVariables();
            log("schema.ctx " + describeVars(ctxVars)); //$NON-NLS-1$
            IBslVariable columnsRu = findNamedVariable(ctxVars, "Колонки"); //$NON-NLS-1$
            IBslVariable columnsEn = findNamedVariable(ctxVars, "Columns"); //$NON-NLS-1$
            IBslVariable columnsVar = columnsRu;
            String foundIn = columnsRu != null ? "ctx.Колонки" : null; //$NON-NLS-1$
            IBslVariable[] allVars = null;
            if (columnsVar == null)
            {
                allVars = indexed.getVariables();
                log("schema.allVars " + describeVars(allVars)); //$NON-NLS-1$
                columnsRu = findNamedVariable(allVars, "Колонки"); //$NON-NLS-1$
                columnsEn = findNamedVariable(allVars, "Columns"); //$NON-NLS-1$
                columnsVar = columnsRu;
                if (columnsRu != null)
                    foundIn = "all.Колонки"; //$NON-NLS-1$
            }
            log("schema.lookup ru=" + (columnsRu != null) + " en=" + (columnsEn != null) //$NON-NLS-1$ //$NON-NLS-2$
                + " foundIn=" + foundIn); //$NON-NLS-1$
            if (columnsVar == null)
            {
                log("schema.no Колонки (enPresent=" + (columnsEn != null) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                return new IBslVariable[0];
            }

            IBslValue columnsValue = columnsVar.getValue();
            log("schema.value " + describeValue(columnsValue)); //$NON-NLS-1$
            if (columnsValue == null)
            {
                log("schema.value null"); //$NON-NLS-1$
                return new IBslVariable[0];
            }

            if (columnsValue instanceof IBslIndexedValue columnsIndexed)
            {
                int colSize = columnsIndexed.getSize();
                log("schema.indexed " + describeIndexed(columnsIndexed)); //$NON-NLS-1$
                if (colSize <= 0)
                {
                    log("schema.indexed empty size=" + colSize); //$NON-NLS-1$
                    return new IBslVariable[0];
                }
                IBslVariable[] defs = columnsIndexed.getVariables();
                log("schema.defs " + describeVars(defs)); //$NON-NLS-1$
                if (allNullOrEmpty(defs) && colSize > 0)
                {
                    int range = Math.min(colSize, MAX_COLUMN_COUNT);
                    defs = columnsIndexed.getVariables(0, range);
                    log("schema.defs.range 0.." + range + " " + describeVars(defs)); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (allNullOrEmpty(defs) && colSize > 0)
                {
                    int range = Math.min(colSize, MAX_COLUMN_COUNT);
                    IBslVariable[] byIndex = new IBslVariable[range];
                    int got = 0;
                    for (int i = 0; i < range; i++)
                    {
                        try
                        {
                            byIndex[i] = DebugCollectionTableModel.itemAt(columnsIndexed, i);
                            if (byIndex[i] != null)
                                got++;
                        }
                        catch (Throwable t)
                        {
                            log("schema.getVariable[" + i + "] " + safe(t)); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                    log("schema.defs.byIndex got=" + got + " " + describeVars(byIndex)); //$NON-NLS-1$ //$NON-NLS-2$
                    if (got > 0)
                        defs = byIndex;
                }
                if (defs == null || defs.length == 0)
                {
                    log("schema.defs empty"); //$NON-NLS-1$
                    return new IBslVariable[0];
                }
                if (defs.length > MAX_COLUMN_COUNT)
                    defs = Arrays.copyOf(defs, MAX_COLUMN_COUNT);
                Map<String, IBslVariable> templates = new LinkedHashMap<>();
                for (IBslVariable def : defs)
                {
                    if (def == null)
                        continue;
                    String name = DebugCollectionPropertyVariables.columnLabel(def);
                    if (name == null || name.isBlank())
                    {
                        log("schema.def skip blank"); //$NON-NLS-1$
                        continue;
                    }
                    templates.putIfAbsent(name, def);
                }
                log("schema.templates n=" + templates.size() + " names=" + templates.keySet()); //$NON-NLS-1$ //$NON-NLS-2$
                return templates.values().toArray(new IBslVariable[0]);
            }

            log("schema.notIndexed cls=" + columnsValue.getClass().getName()); //$NON-NLS-1$
            IBslVariable[] defs = columnsValue.getVariables();
            log("schema.plainDefs " + describeVars(defs)); //$NON-NLS-1$
            if (defs == null || defs.length == 0)
            {
                log("schema.plainDefs empty"); //$NON-NLS-1$
                return new IBslVariable[0];
            }
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
            log("schema.plainTemplates n=" + templates.size() + " names=" + templates.keySet()); //$NON-NLS-1$ //$NON-NLS-2$
            return templates.values().toArray(new IBslVariable[0]);
        }

        static boolean isTabularCollectionType(IBslIndexedValue indexed)
        {
            if (indexed == null)
                return false;
            log("isTabular before getValueTypeName"); //$NON-NLS-1$
            String typeName = indexed.getValueTypeName();
            log("isTabular typeName=" + typeName); //$NON-NLS-1$
            if (typeName == null || typeName.isBlank())
                return false;
            String lower = typeName.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("таблицазначений") //$NON-NLS-1$
                || lower.contains("valuetable") //$NON-NLS-1$
                || lower.contains("деревозначений") //$NON-NLS-1$
                || lower.contains("valuetree"); //$NON-NLS-1$
        }

        private static boolean allNullOrEmpty(IBslVariable[] variables)
        {
            if (variables == null || variables.length == 0)
                return true;
            for (IBslVariable variable : variables)
            {
                if (variable != null)
                    return false;
            }
            return true;
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

        private static void log(String text)
        {
            logColumns( text);
        }

        private static void logCollectionSnapshot(IBslIndexedValue indexed)
        {
            try
            {
                IBslVariable[] ctx = indexed.getContextVariables();
                log("resolve.collection.ctx " + classify(ctx) + " " + describeVars(ctx)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Throwable t)
            {
                logColumnsException( "resolve.collection.ctx", t); //$NON-NLS-1$
            }
            try
            {
                IBslVariable[] vars = indexed.getVariables();
                log("resolve.collection.vars " + classify(vars) + " " + describeVars(vars)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Throwable t)
            {
                logColumnsException( "resolve.collection.vars", t); //$NON-NLS-1$
            }
        }

        private static void logFirstRows(IBslIndexedValue indexed)
        {
            try
            {
                int size = indexed.getSize();
                int n = Math.min(size, 2);
                if (n <= 0)
                {
                    log("resolve.rows none size=" + size); //$NON-NLS-1$
                    return;
                }
                IBslVariable[] rows = indexed.getVariables(0, n);
                log("resolve.rows requested=" + n //$NON-NLS-1$
                    + " got=" + (rows == null ? "null" : Integer.toString(rows.length))); //$NON-NLS-1$ //$NON-NLS-2$
                if (rows == null)
                    return;
                int limit = Math.min(n, rows.length);
                for (int i = 0; i < limit; i++)
                    log("resolve.row[" + i + "] " + describeRow(rows[i])); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Throwable t)
            {
                logColumnsException( "resolve.rows", t); //$NON-NLS-1$
            }
        }

        private static String describeRow(IBslVariable row)
        {
            if (row == null)
                return "null"; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            sb.append("name=").append(row.getName()); //$NON-NLS-1$
            try
            {
                IBslValue value = row.getValue();
                sb.append(' ').append(describeValue(value));
                if (value instanceof IBslIndexedValue indexed)
                {
                    try
                    {
                        IBslVariable[] ctx = indexed.getContextVariables();
                        sb.append(" ctx=").append(classify(ctx)).append(' ').append(describeVars(ctx)); //$NON-NLS-1$
                    }
                    catch (Throwable t)
                    {
                        sb.append(" ctxErr=").append(safe(t)); //$NON-NLS-1$
                    }
                }
                try
                {
                    IBslVariable[] src = DebugCollectionPropertyVariables.propertySource(value);
                    sb.append(" propertySource=").append(classify(src)).append(' ').append(describeVars(src)); //$NON-NLS-1$
                }
                catch (Throwable t)
                {
                    sb.append(" srcErr=").append(safe(t)); //$NON-NLS-1$
                }
            }
            catch (Throwable t)
            {
                sb.append(" err=").append(safe(t)); //$NON-NLS-1$
            }
            return sb.toString();
        }

        static String describeVars(IBslVariable[] vars)
        {
            if (vars == null)
                return "vars=null"; //$NON-NLS-1$
            StringBuilder names = new StringBuilder();
            int shown = Math.min(vars.length, 24);
            for (int i = 0; i < shown; i++)
            {
                if (i > 0)
                    names.append(',');
                IBslVariable variable = vars[i];
                if (variable == null)
                {
                    names.append("null"); //$NON-NLS-1$
                    continue;
                }
                String name = variable.getName();
                names.append(name != null ? name : ""); //$NON-NLS-1$
                try
                {
                    IBslValue value = variable.getValue();
                    if (value == null)
                        names.append("{val=null}"); //$NON-NLS-1$
                    else
                    {
                        names.append("{ev=").append(value.isEvaluated()) //$NON-NLS-1$
                            .append(" pend=").append(value.isPending()) //$NON-NLS-1$
                            .append(" type=").append(value.getValueTypeName()) //$NON-NLS-1$
                            .append(" cls=").append(value.getClass().getSimpleName()); //$NON-NLS-1$
                        if (value instanceof IBslIndexedValue indexed)
                        {
                            try
                            {
                                names.append(" size=").append(indexed.getSize()); //$NON-NLS-1$
                            }
                            catch (Throwable ignored)
                            {
                            }
                        }
                        names.append('}');
                    }
                }
                catch (Throwable t)
                {
                    names.append("{err=").append(safe(t)).append('}'); //$NON-NLS-1$
                }
            }
            if (vars.length > shown)
                names.append(",…+").append(vars.length - shown); //$NON-NLS-1$
            return "n=" + vars.length + " [" + names + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String describeIndexed(IBslIndexedValue indexed)
        {
            if (indexed == null)
                return "null"; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            sb.append("cls=").append(indexed.getClass().getName()); //$NON-NLS-1$
            try
            {
                sb.append(" type=").append(indexed.getValueTypeName()); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                sb.append(" typeErr=").append(safe(t)); //$NON-NLS-1$
            }
            try
            {
                sb.append(" size=").append(indexed.getSize()); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                sb.append(" sizeErr=").append(safe(t)); //$NON-NLS-1$
            }
            try
            {
                sb.append(" ev=").append(indexed.isEvaluated()); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                sb.append(" evErr=").append(safe(t)); //$NON-NLS-1$
            }
            try
            {
                sb.append(" pend=").append(indexed.isPending()); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                sb.append(" pendErr=").append(safe(t)); //$NON-NLS-1$
            }
            return sb.toString();
        }

        private static String describeValue(IBslValue value)
        {
            if (value == null)
                return "val=null"; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            sb.append("valCls=").append(value.getClass().getName()); //$NON-NLS-1$
            try
            {
                sb.append(" type=").append(value.getValueTypeName()); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                sb.append(" typeErr=").append(safe(t)); //$NON-NLS-1$
            }
            try
            {
                sb.append(" ev=").append(value.isEvaluated()); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                sb.append(" evErr=").append(safe(t)); //$NON-NLS-1$
            }
            try
            {
                sb.append(" pend=").append(value.isPending()); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                sb.append(" pendErr=").append(safe(t)); //$NON-NLS-1$
            }
            if (value instanceof IBslIndexedValue indexed)
            {
                try
                {
                    sb.append(" size=").append(indexed.getSize()); //$NON-NLS-1$
                }
                catch (Throwable t)
                {
                    sb.append(" sizeErr=").append(safe(t)); //$NON-NLS-1$
                }
            }
            return sb.toString();
        }

        private static String classify(IBslVariable[] context)
        {
            if (context == null)
                return "null"; //$NON-NLS-1$
            if (context.length == 0)
                return "empty"; //$NON-NLS-1$
            boolean meta = DebugCollectionPropertyVariables.isRowMetadataContext(context);
            boolean placeholder = DebugCollectionPropertyVariables.isIndexedPlaceholderContext(context);
            boolean acceptable = DebugCollectionPropertyVariables.isAcceptableColumnContext(context);
            return "n=" + context.length //$NON-NLS-1$
                + " meta=" + meta //$NON-NLS-1$
                + " ph=" + placeholder //$NON-NLS-1$
                + " acc=" + acceptable; //$NON-NLS-1$
        }

        private static String safe(Throwable t)
        {
            if (t == null)
                return "null"; //$NON-NLS-1$
            String message = t.getMessage();
            return t.getClass().getSimpleName() + ":" + (message != null ? message : ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
