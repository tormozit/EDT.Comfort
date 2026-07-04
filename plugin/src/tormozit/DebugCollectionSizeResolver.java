package tormozit;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Async {@code getSize()} для вложенных коллекций в ячейках таблицы (pass 2).
 * <p>
 * Каждый чанк (до 2 ячеек) вызывает {@code evaluate()}, затем поллинг {@code isPending()}
 * с паузой 100мс в том же Job-треде. Как только evaluate завершён — {@code getSize()}
 * отдаёт реальный размер. Первый resolved-элемент показывается через ~200 мс.
 */
final class DebugCollectionSizeResolver
{
    private static final int CELLS_PER_CHUNK = 2;

    private static final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

    private DebugCollectionSizeResolver() {}

    static void cancelAll()
    {
        cancelled.set(true);
    }

    static void resetForNewWindow()
    {
        cancelled.set(false);
    }

    static void scheduleBatch(
        DebugCollectionTableModel model,
        int rowFrom,
        int rowCount,
        int colFrom,
        int colTo,
        Display display,
        Runnable onRowsReady)
    {
        if (model == null || display == null || display.isDisposed() || cancelled.get())
            return;
        if (colTo < colFrom)
            return;
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        for (int row = rowFrom; row < rowFrom + rowCount; row++)
        {
            for (int col = colFrom; col <= colTo; col++)
            {
                if (isSizePassSkippedColumn(model, col))
                    continue;
                cells.add(new int[] { row, col });
            }
        }
        int chunkCount = (cells.size() + CELLS_PER_CHUNK - 1) / CELLS_PER_CHUNK;
        for (int i = 0; i < chunkCount; i++)
        {
            int from = i * CELLS_PER_CHUNK;
            int to = Math.min(cells.size(), from + CELLS_PER_CHUNK);
            java.util.List<int[]> chunkCells = cells.subList(from, to);
            scheduleChunk(chunkCells, i, model, display, onRowsReady);
        }
    }

    private static void scheduleChunk(
        java.util.List<int[]> chunkCells,
        int chunkIndex,
        DebugCollectionTableModel model,
        Display display,
        Runnable onRowsReady)
    {
        if (display == null || display.isDisposed() || cancelled.get())
            return;
        Job.create("Комфорт: размер ячеек коллекции", monitor -> { //$NON-NLS-1$
            if (cancelled.get() || monitor.isCanceled())
                return org.eclipse.core.runtime.Status.OK_STATUS;
            int resolved = 0;
            for (int[] cell : chunkCells)
            {
                if (monitor.isCanceled() || cancelled.get())
                    break;
                ResolveOutcome outcome = resolveCellSize(model, cell[0], cell[1]);
                if ("size".equals(outcome.outcome)) //$NON-NLS-1$
                {
                    resolved++;
                    logSizeCellSample(cell[0], cell[1], outcome);
                }
            }
            // #region agent log
            DebugCollectionAgentLog.log("H-sizeChunk", "SizeResolver.scheduleChunk", "chunkDone", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"chunk\":" + chunkIndex //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"cells\":" + chunkCells.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"resolved\":" + resolved + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            if (resolved > 0 && onRowsReady != null && !display.isDisposed() && !cancelled.get())
                display.asyncExec(onRowsReady);
            return org.eclipse.core.runtime.Status.OK_STATUS;
        }).schedule();
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

        ResolveOutcome(String outcome, int size)
        {
            this.outcome = outcome;
            this.size = size;
        }
    }

    private static boolean isSizePassSkippedColumn(DebugCollectionTableModel model, int col)
    {
        DebugCollectionColumnModel.Column column = model.columns.columnAt(col);
        if (column == null)
            return false;
        return column.kind == DebugCollectionColumnModel.Kind.INDEX
            || column.kind == DebugCollectionColumnModel.Kind.TYPE;
    }

    /** @return outcome + size when {@code size} */
    private static ResolveOutcome resolveCellSize(DebugCollectionTableModel model, int row, int col)
    {
        DebugCollectionTableModel.CellData data = model.cellData(row, col);
        try
        {
            synchronized (data)
            {
                if (data.sizeState == DebugCollectionTableModel.SizeState.READY
                    || data.sizeState == DebugCollectionTableModel.SizeState.NA)
                    return new ResolveOutcome("skip", -1); //$NON-NLS-1$
            }
            // Ждём cell content (SetData на UI-треде), до 3с
            for (int wait = 0; wait < 30; wait++)
            {
                boolean loaded;
                synchronized (data) { loaded = data.contentLoaded; }
                if (loaded)
                    break;
                if (cancelled.get())
                    return new ResolveOutcome("skip", -1); //$NON-NLS-1$
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new ResolveOutcome("skip", -1); }
            }
            synchronized (data)
            {
                if (!data.contentLoaded)
                {
                    data.sizeState = DebugCollectionTableModel.SizeState.UNKNOWN;
                    return new ResolveOutcome("pending", -1); //$NON-NLS-1$
                }
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
            // Poll каждые 100мс пока evaluate() не завершится (до 6с)
            if (value.isPending())
            {
                for (int poll = 0; poll < 60; poll++)
                {
                    if (cancelled.get())
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
                return new ResolveOutcome("size", size); //$NON-NLS-1$
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
            // Не даём одной сбойнувшейся ячейке убить весь Job чанка.
            DebugCollectionDebug.problem("cellSize (runtime): " + e); //$NON-NLS-1$
            synchronized (data)
            {
                data.sizeState = DebugCollectionTableModel.SizeState.UNKNOWN;
            }
            return new ResolveOutcome("error", -1); //$NON-NLS-1$
        }
    }
}
