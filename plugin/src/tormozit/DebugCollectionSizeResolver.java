package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Async {@code getSize()} для вложенных коллекций в ячейках таблицы (pass 2).
 * <p>
 * Один pass-Job на viewport, до {@link #MAX_CONCURRENT_EVALUATE} параллельных {@code evaluate()},
 * приоритет видимых строк, отмена устаревших проходов по generation.
 */
final class DebugCollectionSizeResolver
{
    private static final int MAX_CONCURRENT_EVALUATE = 1;
    private static final int EVALUATE_POLL_MAX = 60;
    private static final int BATCH_SIZE = 6;

    private static final AtomicInteger cancelled = new AtomicInteger();
    private static final AtomicInteger activeGeneration = new AtomicInteger(-1);
    private static volatile Job passJob;
    private static final ExecutorService evaluateExecutor = Executors.newFixedThreadPool(
        MAX_CONCURRENT_EVALUATE, new ThreadFactory()
        {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r)
            {
                Thread t = new Thread(r, "Comfort-CollectionSize-" + seq.incrementAndGet()); //$NON-NLS-1$
                t.setDaemon(true);
                return t;
            }
        });

    private DebugCollectionSizeResolver() {}

    static void cancelAll()
    {
        cancelled.set(1);
        cancelPass(-1);
    }

    static void resetForNewWindow()
    {
        cancelled.set(0);
        activeGeneration.set(-1);
        passJob = null;
    }

    static boolean isPassBusy()
    {
        Job job = passJob;
        if (job == null)
            return false;
        int state = job.getState();
        return state == Job.RUNNING || state == Job.WAITING || state == Job.SLEEPING;
    }

    /** Отменить текущий pass; {@code exceptGeneration} — generation нового прохода (не отменять свой). */
    static void cancelPass(int exceptGeneration)
    {
        int prev = activeGeneration.get();
        if (prev != exceptGeneration && prev >= 0)
        {
            // #region agent log
            DebugCollectionAgentLog.log("H-sizePass", "SizeResolver.cancelPass", "cancel", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"prevGen\":" + prev + ",\"newGen\":" + exceptGeneration + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
        }
        activeGeneration.set(exceptGeneration);
        Job job = passJob;
        if (job != null)
        {
            job.cancel();
            passJob = null;
        }
    }

    static void schedulePass(
        DebugCollectionTableModel model,
        int coreRowFrom,
        int coreRowTo,
        int overscanRowFrom,
        int overscanRowTo,
        int colFrom,
        int colTo,
        Display display,
        int generation,
        boolean cancelPrevious,
        boolean includeOverscan,
        String reason,
        int viewportGen,
        IntConsumer onRowResolved,
        Runnable onRowsReady,
        java.util.function.Consumer<Boolean> onPassFinished)
    {
        if (model == null || display == null || display.isDisposed() || cancelled.get() != 0)
            return;
        if (colTo < colFrom || coreRowTo < coreRowFrom)
            return;
        if (cancelPrevious)
            cancelPass(generation);
        else if (isPassBusy())
            return;
        List<int[]> cells = buildPriorityCellList(
            model, coreRowFrom, coreRowTo, overscanRowFrom, overscanRowTo, colFrom, colTo, includeOverscan);
        if (cells.isEmpty())
            return;
        final long startMs = System.currentTimeMillis();
        final String passReason = reason != null ? reason : ""; //$NON-NLS-1$
        // #region agent log
        DebugCollectionAgentLog.log("H-sizePass", "SizeResolver.schedulePass", "start", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"generation\":" + generation //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"queued\":" + cells.size() //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"coreFrom\":" + coreRowFrom + ",\"coreTo\":" + coreRowTo //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"workers\":" + MAX_CONCURRENT_EVALUATE //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"reason\":\"" + passReason + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"viewportGen\":" + viewportGen //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"includeOverscan\":" + includeOverscan + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
        final Job[] created = new Job[1];
        created[0] = Job.create("Комфорт: размер ячеек коллекции", monitor -> { //$NON-NLS-1$
            if (cancelled.get() != 0 || monitor.isCanceled() || generation != activeGeneration.get())
                return org.eclipse.core.runtime.Status.OK_STATUS;
            int resolved = 0;
            int skipped = 0;
            long[] lastHeartbeatMs = { 0L };
            Set<Integer> batchRows = new LinkedHashSet<>();
            // Раньше: партиями по BATCH_SIZE, с барьером — весь пул ждал, пока ДОСЧИТАЮТСЯ все
            // ячейки текущей партии, прежде чем в пул уходила следующая. Если хоть одна ячейка партии
            // упиралась в полный EVALUATE_POLL_MAX (6с), остальные 5 воркеров всё это время простаивали,
            // хотя могли бы уже забирать следующие ячейки. Видели в логе: +6 ячеек ровно каждые 6с подряд
            // несколько раз — явный барьерный эффект. Теперь все ячейки отправляются в пул сразу,
            // а результаты разбираются по мере готовности (ExecutorCompletionService) — воркер,
            // освободившийся раньше остальных, тут же берёт следующую ячейку, не дожидаясь соседей.
            CompletionService<ResolveOutcome> completionService = new ExecutorCompletionService<>(evaluateExecutor);
            int submitted = 0;
            for (int[] cell : cells)
            {
                final int row = cell[0];
                final int col = cell[1];
                completionService.submit((Callable<ResolveOutcome>) () -> {
                    if (cancelled.get() != 0 || generation != activeGeneration.get())
                        return new ResolveOutcome("stale", -1); //$NON-NLS-1$
                    return resolveCellSize(model, row, col, generation);
                });
                submitted++;
            }
            for (int processed = 0; processed < submitted; processed++)
            {
                if (monitor.isCanceled() || cancelled.get() != 0 || generation != activeGeneration.get())
                    break;
                Future<ResolveOutcome> future;
                try
                {
                    future = completionService.poll(200, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (future == null)
                {
                    processed--; // ничего не готово за этот тик — не считаем как обработанное, ждём ещё
                    continue;
                }
                try
                {
                    ResolveOutcome outcome = future.get();
                    if ("size".equals(outcome.outcome)) //$NON-NLS-1$
                    {
                        resolved++;
                        if (outcome.row >= 0)
                            batchRows.add(outcome.row);
                        logSizeCellSample(outcome.row, outcome.col, outcome);
                    }
                    else if ("pending".equals(outcome.outcome)) //$NON-NLS-1$
                        skipped++;
                }
                catch (Exception e)
                {
                    if (!(e instanceof InterruptedException))
                        DebugCollectionDebug.problem("sizePass future: " + e); //$NON-NLS-1$
                    Thread.currentThread().interrupt();
                }
                // Отдаём в UI небольшими порциями по мере готовности, не дожидаясь всего прохода.
                if (batchRows.size() >= BATCH_SIZE && onRowResolved != null && !display.isDisposed() && cancelled.get() == 0)
                {
                    int[] rows = batchRows.stream().mapToInt(Integer::intValue).toArray();
                    batchRows.clear();
                    final int batchGen = generation;
                    display.asyncExec(() -> {
                        if (cancelled.get() != 0 || batchGen != activeGeneration.get())
                            return;
                        for (int row : rows)
                            onRowResolved.accept(row);
                    });
                }
                // Heartbeat для долгих проходов — без этого проход глубоко в коллекции может
                // молчать 15-20+ секунд без единой записи в логе, пока его не отменят/он завершится;
                // isPassBusy() в это время блокирует любые не-priority проходы.
                long elapsedMs = System.currentTimeMillis() - startMs;
                if (elapsedMs - lastHeartbeatMs[0] >= 2000)
                {
                    lastHeartbeatMs[0] = elapsedMs;
                    // #region agent log
                    DebugCollectionAgentLog.log("H-sizePass", "SizeResolver.schedulePass", "heartbeat", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "{\"generation\":" + generation //$NON-NLS-1$ //$NON-NLS-2$
                            + ",\"elapsedMs\":" + elapsedMs //$NON-NLS-1$ //$NON-NLS-2$
                            + ",\"processed\":" + (processed + 1) + ",\"of\":" + cells.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + ",\"resolvedSoFar\":" + resolved + ",\"skippedSoFar\":" + skipped + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    // #endregion
                }
            }
            // Хвост — то, что не набралось до BATCH_SIZE перед выходом из цикла.
            if (!batchRows.isEmpty() && onRowResolved != null && !display.isDisposed() && cancelled.get() == 0)
            {
                int[] rows = batchRows.stream().mapToInt(Integer::intValue).toArray();
                final int batchGen = generation;
                display.asyncExec(() -> {
                    if (cancelled.get() != 0 || batchGen != activeGeneration.get())
                        return;
                    for (int row : rows)
                        onRowResolved.accept(row);
                });
            }
            boolean needsRetry = skipped > 0;
            boolean stale = generation != activeGeneration.get();
            long durationMs = System.currentTimeMillis() - startMs;
            // #region agent log
            if (!stale)
            {
                DebugCollectionAgentLog.log("H-sizePass", "SizeResolver.schedulePass", "done", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"generation\":" + generation //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"resolved\":" + resolved //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"skipped\":" + skipped //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"needsRetry\":" + needsRetry //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"reason\":\"" + passReason + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"viewportGen\":" + viewportGen //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"durationMs\":" + durationMs + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            // #endregion
            if (!stale && resolved > 0 && onRowsReady != null && !display.isDisposed() && cancelled.get() == 0)
                display.asyncExec(onRowsReady);
            if (!stale && onPassFinished != null)
                onPassFinished.accept(needsRetry);
            if (passJob == created[0])
                passJob = null;
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        passJob = created[0];
        created[0].setSystem(true);
        created[0].schedule();
    }

    private static List<int[]> buildPriorityCellList(
        DebugCollectionTableModel model,
        int coreRowFrom,
        int coreRowTo,
        int overscanRowFrom,
        int overscanRowTo,
        int colFrom,
        int colTo,
        boolean includeOverscan)
    {
        List<int[]> cells = new ArrayList<>();
        appendRowRangeCells(model, cells, coreRowFrom, coreRowTo, colFrom, colTo);
        if (includeOverscan)
        {
            if (overscanRowFrom < coreRowFrom)
                appendRowRangeCells(model, cells, overscanRowFrom, coreRowFrom - 1, colFrom, colTo);
            if (overscanRowTo > coreRowTo)
                appendRowRangeCells(model, cells, coreRowTo + 1, overscanRowTo, colFrom, colTo);
        }
        return cells;
    }

    private static void appendRowRangeCells(
        DebugCollectionTableModel model,
        List<int[]> cells,
        int rowFrom,
        int rowTo,
        int colFrom,
        int colTo)
    {
        if (rowTo < rowFrom)
            return;
        for (int row = rowFrom; row <= rowTo; row++)
        {
            for (int col = colFrom; col <= colTo; col++)
            {
                if (isSizePassSkippedColumn(model, col))
                    continue;
                cells.add(new int[] { row, col });
            }
        }
    }

    private static void logSizeCellSample(int row, int col, ResolveOutcome outcome)
    {
        if (row % 64 != 0 && row < 1700)
            return;
        // #region agent log
        DebugCollectionAgentLog.log("H-sizeCell", "SizeResolver.resolveCellSize", "resolved", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"row\":" + row + ",\"col\":" + col //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"size\":" + outcome.size + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private static final class ResolveOutcome
    {
        final String outcome;
        final int size;
        final int row;
        final int col;

        ResolveOutcome(String outcome, int size)
        {
            this(outcome, size, -1, -1);
        }

        ResolveOutcome(String outcome, int size, int row, int col)
        {
            this.outcome = outcome;
            this.size = size;
            this.row = row;
            this.col = col;
        }
    }

    static boolean isSizePassSkippedColumn(DebugCollectionTableModel model, int col)
    {
        DebugCollectionColumnModel.Column column = model.columns.columnAt(col);
        if (column == null)
            return false;
        return column.kind == DebugCollectionColumnModel.Kind.INDEX
            || column.kind == DebugCollectionColumnModel.Kind.TYPE;
    }

    /** @return outcome + size when {@code size} */
    private static ResolveOutcome resolveCellSize(
        DebugCollectionTableModel model, int row, int col, int generation)
    {
        if (cancelled.get() != 0 || generation != activeGeneration.get())
            return new ResolveOutcome("stale", -1); //$NON-NLS-1$
        DebugCollectionTableModel.CellData data = model.cellData(row, col);
        try
        {
            synchronized (data)
            {
                if (data.sizeState == DebugCollectionTableModel.SizeState.READY
                    || data.sizeState == DebugCollectionTableModel.SizeState.NA)
                    return new ResolveOutcome("skip", -1); //$NON-NLS-1$
            }
            boolean loaded;
            synchronized (data) { loaded = data.contentLoaded; }
            if (!loaded)
            {
                synchronized (data)
                {
                    data.sizeState = DebugCollectionTableModel.SizeState.UNKNOWN;
                }
                return new ResolveOutcome("pending", -1); //$NON-NLS-1$
            }
            model.markCellSizePending(row, col);
            IBslValue value = model.resolveCellValue(row, col);
            if (value == null)
            {
                synchronized (data)
                {
                    data.sizeState = DebugCollectionTableModel.SizeState.NA;
                }
                return new ResolveOutcome("na", -1); //$NON-NLS-1$
            }
            if (!value.isEvaluated())
                value.evaluate();
            if (value.isPending())
            {
                for (int poll = 0; poll < EVALUATE_POLL_MAX; poll++)
                {
                    if (cancelled.get() != 0 || generation != activeGeneration.get())
                        break;
                    try
                    {
                        Thread.sleep(100);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (!value.isPending())
                        break;
                }
            }
            if (value.isPending())
            {
                synchronized (data)
                {
                    data.sizeState = DebugCollectionTableModel.SizeState.UNKNOWN;
                }
                return new ResolveOutcome("pending", -1); //$NON-NLS-1$
            }
            if (!(value instanceof IBslIndexedValue indexed))
            {
                synchronized (data)
                {
                    data.sizeState = DebugCollectionTableModel.SizeState.NA;
                }
                return new ResolveOutcome("na", -1); //$NON-NLS-1$
            }
            int size = indexed.getSize();
            if (size >= 0)
            {
                model.setCellSize(row, col, size);
                return new ResolveOutcome("size", size, row, col); //$NON-NLS-1$
            }
            synchronized (data)
            {
                data.sizeState = DebugCollectionTableModel.SizeState.UNKNOWN;
            }
            return new ResolveOutcome("pending", -1); //$NON-NLS-1$
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("cellSize: " + e.getMessage()); //$NON-NLS-1$
            synchronized (data)
            {
                data.sizeState = DebugCollectionTableModel.SizeState.UNKNOWN;
            }
            return new ResolveOutcome("error", -1); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            DebugCollectionDebug.problem("cellSize (runtime): " + e); //$NON-NLS-1$
            synchronized (data)
            {
                data.sizeState = DebugCollectionTableModel.SizeState.UNKNOWN;
            }
            return new ResolveOutcome("error", -1); //$NON-NLS-1$
        }
    }
}
