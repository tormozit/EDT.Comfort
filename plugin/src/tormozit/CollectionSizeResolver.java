package tormozit;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Async {@code getSize()} для вложенных коллекций в ячейках таблицы (pass 2).
 */
final class CollectionSizeResolver
{
    private static final int COL_CHUNK = 12;
    private static final int PENDING_RETRY_DELAY_MS = 300;
    private static final int MAX_PENDING_RETRIES = 12;

    private static final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

    private CollectionSizeResolver() {}

    static void cancelAll()
    {
        cancelled.set(true);
    }

    static void resetForNewWindow()
    {
        cancelled.set(false);
    }

    static void scheduleBatch(
        ComfortCollectionTableModel model,
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
        ComfortCollectionTableModel model,
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
        if (colTo < colFrom)
            return;
        Job.create("Комфорт: размер ячеек коллекции", monitor -> { //$NON-NLS-1$
            if (cancelled.get() || monitor.isCanceled())
                return org.eclipse.core.runtime.Status.OK_STATUS;
            int resolved = 0;
            int pending = 0;
            for (int row = rowFrom; row < rowFrom + rowCount; row++)
            {
                if (monitor.isCanceled() || cancelled.get())
                    break;
                for (int chunkFrom = colFrom; chunkFrom <= colTo; chunkFrom += COL_CHUNK)
                {
                    int chunkTo = Math.min(colTo, chunkFrom + COL_CHUNK - 1);
                    for (int col = chunkFrom; col <= chunkTo; col++)
                    {
                        if (isIndexColumn(model, col))
                            continue;
                        String outcome = resolveCellSize(model, row, col);
                        switch (outcome)
                        {
                            case "size" -> resolved++; //$NON-NLS-1$
                            case "pending" -> pending++; //$NON-NLS-1$
                            default -> { }
                        }
                    }
                }
            }
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

    private static boolean isIndexColumn(ComfortCollectionTableModel model, int col)
    {
        CollectionColumnModel.Column column = model.columns.columnAt(col);
        return column != null && column.kind == CollectionColumnModel.Kind.INDEX;
    }

    /** @return {@code size} | {@code na} | {@code pending} | {@code skip} | {@code error} */
    private static String resolveCellSize(ComfortCollectionTableModel model, int row, int col)
    {
        ComfortCollectionTableModel.CellData data = model.cellData(row, col);
        try
        {
            synchronized (data)
            {
                if (data.sizeState == ComfortCollectionTableModel.SizeState.READY
                    || data.sizeState == ComfortCollectionTableModel.SizeState.NA)
                    return "skip"; //$NON-NLS-1$
                if (!data.contentLoaded)
                {
                    synchronized (data)
                    {
                        data.sizeState = ComfortCollectionTableModel.SizeState.UNKNOWN;
                    }
                    return "pending"; //$NON-NLS-1$
                }
            }
            model.markCellSizePending(row, col);
            IBslValue value = model.resolveCellValue(row, col);
            if (value == null)
            {
                synchronized (data)
                {
                    data.sizeState = ComfortCollectionTableModel.SizeState.NA;
                }
                return "na"; //$NON-NLS-1$
            }
            if (value.isPending())
            {
                synchronized (data)
                {
                    data.sizeState = ComfortCollectionTableModel.SizeState.UNKNOWN;
                }
                return "pending"; //$NON-NLS-1$
            }
            if (!value.isEvaluated())
                value.evaluate();
            if (value.isPending())
            {
                synchronized (data)
                {
                    data.sizeState = ComfortCollectionTableModel.SizeState.UNKNOWN;
                }
                return "pending"; //$NON-NLS-1$
            }
            if (!(value instanceof IBslIndexedValue indexed))
            {
                synchronized (data)
                {
                    data.sizeState = ComfortCollectionTableModel.SizeState.NA;
                }
                return "na"; //$NON-NLS-1$
            }
            int size = indexed.getSize();
            if (size >= 0)
            {
                model.setCellSize(row, col, size);
                return "size"; //$NON-NLS-1$
            }
            synchronized (data)
            {
                data.sizeState = ComfortCollectionTableModel.SizeState.UNKNOWN;
            }
            return "pending"; //$NON-NLS-1$
        }
        catch (DebugException e)
        {
            ComfortCollectionDebug.problem("cellSize: " + e.getMessage()); //$NON-NLS-1$
            synchronized (data)
            {
                data.sizeState = ComfortCollectionTableModel.SizeState.UNKNOWN;
            }
            return "error"; //$NON-NLS-1$
        }
    }
}
