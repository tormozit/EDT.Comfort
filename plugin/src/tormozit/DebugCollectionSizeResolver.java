package tormozit;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Async {@code getSize()} для вложенных коллекций в ячейках таблицы (pass 2).
 */
final class DebugCollectionSizeResolver
{
    private static final int COL_CHUNK = 12;
    private static final int PENDING_RETRY_DELAY_MS = 300;
    private static final int MAX_PENDING_RETRIES = 12;

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
        scheduleBatch(model, rowFrom, rowCount, colFrom, colTo, display, onRowsReady, 0);
    }

    private static void scheduleBatch(
        DebugCollectionTableModel model,
        int rowFrom,
        int rowCount,
        int colFrom,
        int colTo,
        Display display,
        Runnable onRowsReady,
        int retryAttempt)
    {
        if (model == null || display == null || display.isDisposed() || cancelled.get())
            return;
        if (colTo < colFrom)
            return;
        Job.create("Комфорт: размер ячеек коллекции", monitor -> { //$NON-NLS-1$
            if (cancelled.get() || monitor.isCanceled())
                return org.eclipse.core.runtime.Status.OK_STATUS;
            int resolved = 0;
            int pending = 0;
            int skip = 0;
            int na = 0;
            int error = 0;
            for (int row = rowFrom; row < rowFrom + rowCount; row++)
            {
                if (monitor.isCanceled() || cancelled.get())
                    break;
                for (int chunkFrom = colFrom; chunkFrom <= colTo; chunkFrom += COL_CHUNK)
                {
                    int chunkTo = Math.min(colTo, chunkFrom + COL_CHUNK - 1);
                    for (int col = chunkFrom; col <= chunkTo; col++)
                    {
                        if (isSizePassSkippedColumn(model, col))
                            continue;
                        ResolveOutcome outcome = resolveCellSize(model, row, col);
                        switch (outcome.outcome)
                        {
                            case "size" -> { //$NON-NLS-1$
                                resolved++;
                                logSizeCellSample(row, col, outcome);
                            }
                            case "pending" -> pending++; //$NON-NLS-1$
                            case "skip" -> skip++; //$NON-NLS-1$
                            case "na" -> na++; //$NON-NLS-1$
                            case "error" -> error++; //$NON-NLS-1$
                            default -> { }
                        }
                    }
                }
            }
            // #region agent log
            DebugCollectionAgentLog.log("H-sizeBatch", "SizeResolver.scheduleBatch", "done", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"rowFrom\":" + rowFrom + ",\"rowCount\":" + rowCount //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"colFrom\":" + colFrom + ",\"colTo\":" + colTo //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"retry\":" + retryAttempt //$NON-NLS-1$
                    + ",\"resolved\":" + resolved + ",\"pending\":" + pending //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"skip\":" + skip + ",\"na\":" + na + ",\"error\":" + error + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            if (pending > 0 && retryAttempt < MAX_PENDING_RETRIES && !cancelled.get() && !monitor.isCanceled())
            {
                Job retry = Job.create("Комфорт: размер ячеек коллекции (повтор)", m -> { //$NON-NLS-1$
                    scheduleBatch(model, rowFrom, rowCount, colFrom, colTo, display, onRowsReady, retryAttempt + 1);
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                });
                retry.schedule(PENDING_RETRY_DELAY_MS);
            }
            if ((resolved > 0 || pending > 0) && onRowsReady != null && !display.isDisposed() && !cancelled.get())
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
            if (value.isPending())
            {
                synchronized (data)
                {
                    data.sizeState = DebugCollectionTableModel.SizeState.UNKNOWN;
                }
                return new ResolveOutcome("pending", -1); //$NON-NLS-1$
            }
            if (!value.isEvaluated())
                value.evaluate();
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
    }
}
