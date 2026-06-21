package tormozit;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Повторное форматирование строковых ячеек после async-загрузки detail (префикс {@code [N]}).
 */
final class DebugCollectionStringFormatResolver
{
    private static final int COL_CHUNK = 12;
    private static final int PENDING_RETRY_DELAY_MS = 300;
    private static final int MAX_PENDING_RETRIES = 12;

    private static final java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();

    private DebugCollectionStringFormatResolver() {}

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
        if (colTo < colFrom || rowCount <= 0)
            return;
        Job.create("Комфорт: формат строк коллекции", monitor -> { //$NON-NLS-1$
            if (cancelled.get() || monitor.isCanceled())
                return org.eclipse.core.runtime.Status.OK_STATUS;
            int updated = 0;
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
                        String outcome = resolveCellFormat(model, row, col);
                        switch (outcome)
                        {
                            case "updated" -> updated++; //$NON-NLS-1$
                            case "pending" -> pending++; //$NON-NLS-1$
                            default -> { }
                        }
                    }
                }
            }
            if (pending > 0 && retryAttempt < MAX_PENDING_RETRIES && !cancelled.get() && !monitor.isCanceled())
            {
                Job retry = Job.create("Комфорт: формат строк коллекции (повтор)", m -> { //$NON-NLS-1$
                    scheduleBatch(model, rowFrom, rowCount, colFrom, colTo, display, onRowsReady,
                        retryAttempt + 1);
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                });
                retry.schedule(PENDING_RETRY_DELAY_MS);
            }
            if ((updated > 0 || pending > 0) && onRowsReady != null && !display.isDisposed() && !cancelled.get())
                display.asyncExec(onRowsReady);
            return org.eclipse.core.runtime.Status.OK_STATUS;
        }).schedule();
    }

    /** @return {@code updated} | {@code pending} | {@code skip} | {@code error} */
    private static String resolveCellFormat(DebugCollectionTableModel model, int row, int col)
    {
        DebugCollectionColumnModel.Column column = model.columns.columnAt(col);
        if (column != null && column.kind == DebugCollectionColumnModel.Kind.INDEX)
            return "skip"; //$NON-NLS-1$
        if (!model.isCellFilled(row, col))
            return "skip"; //$NON-NLS-1$
        try
        {
            IBslValue value = model.resolveCellValue(row, col);
            if (value == null || !DebugStringValueFormat.isStringValue(value))
                return "skip"; //$NON-NLS-1$
            String displayed = model.getCellBaseText(row, col);
            if (displayed == null)
                displayed = ""; //$NON-NLS-1$
            String formatted = DebugStringValueFormat.formatForCollectionWindow(displayed, value);
            if (!formatted.equals(displayed))
            {
                model.setCellText(row, col, formatted);
                return "updated"; //$NON-NLS-1$
            }
            if (DebugStringValueFormat.needsStringFormatRetry(value, displayed))
                return "pending"; //$NON-NLS-1$
            return "skip"; //$NON-NLS-1$
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("stringFormat: " + e.getMessage()); //$NON-NLS-1$
            return "error"; //$NON-NLS-1$
        }
    }
}
