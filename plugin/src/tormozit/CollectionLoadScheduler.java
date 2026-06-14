package tormozit;

import java.util.function.Consumer;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;

/**
 * Фоновая подгрузка строк коллекции micro-порциями; UI thread не блокируется.
 */
final class CollectionLoadScheduler
{
    static final int BATCH_SIZE = 24;
    static final int OVERSCAN = 8;
    /** Автоподгрузка browse/prefetch; фильтр и полный обход — без cap. */
    static final int AUTO_LOAD_ROW_LIMIT = 1000;

    interface ProgressListener
    {
        void onProgress(int loaded, int total, String phase);

        void onRowsReady(int first, int count);
    }

    private final ComfortCollectionTableModel model;
    private final Display display;
    private final ProgressListener progress;
    private final Consumer<IBslVariable[]> contextColumnsListener;
    private Table tableForViewport;
    private Shell shellForPause;
    private final java.util.concurrent.atomic.AtomicBoolean disposed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger viewportFirst = new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger viewportLast = new java.util.concurrent.atomic.AtomicInteger(-1);
    /** Диапазон видимых колонок — только с UI thread, Job читает эти значения. */
    private final java.util.concurrent.atomic.AtomicInteger viewportColFrom = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger viewportColTo = new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicBoolean loadPending = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Снимок видимости shell — только с UI thread, Job читает эти значения. */
    private final java.util.concurrent.atomic.AtomicBoolean shellVisible = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicBoolean shellMinimized = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Listener shellStateListener;
    private volatile Job loadJob;
    private volatile Job sizeJob;
    private volatile Job filterJob;
    private volatile Job contextJob;
    private final java.util.concurrent.atomic.AtomicInteger contextResolveAttempts =
        new java.util.concurrent.atomic.AtomicInteger();

    CollectionLoadScheduler(
        ComfortCollectionTableModel model,
        Display display,
        ProgressListener progress,
        Consumer<IBslVariable[]> contextColumnsListener)
    {
        this.model = model;
        this.display = display;
        this.progress = progress;
        this.contextColumnsListener = contextColumnsListener;
        CollectionSizeResolver.resetForNewWindow();
        contextResolveAttempts.set(0);
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
                // Hide может прийти от Win32 pin без реального скрытия — перечитываем после события.
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

    /** Снимок видимости shell — только с UI thread. */
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
        updateShellActiveSnapshot();
        viewportFirst.set(0);
        viewportLast.set(BATCH_SIZE + OVERSCAN);
        captureColumnViewport();
        scheduleContextJob();
        scheduleSizeJob();
        scheduleLoadJob("initial"); //$NON-NLS-1$
    }

    void requestViewport(int first, int last)
    {
        if (disposed.get() || !isShellActiveForLoad())
            return;
        viewportFirst.set(Math.max(0, first - OVERSCAN));
        viewportLast.set(last + OVERSCAN);
        captureColumnViewport();
        scheduleLoadJob("viewport"); //$NON-NLS-1$
    }

    /** Снимок горизонтального viewport — только с UI thread. */
    void captureColumnViewport()
    {
        Table table = tableForViewport;
        int maxCol = Math.max(0, model.columns.columnCount() - 1);
        if (table == null || table.isDisposed())
        {
            viewportColFrom.set(0);
            viewportColTo.set(maxCol);
            return;
        }
        int dataLast = CollectionViewportTracker.lastVisibleColumn(table);
        viewportColFrom.set(0);
        viewportColTo.set(Math.max(0, dataLast + 1));
    }

    void scheduleFilterScan(CollectionRowFilter filter, Runnable onDone)
    {
        if (disposed.get() || filter == null)
            return;
        cancelFilterJob();
        filterJob = Job.create("Комфорт: фильтр коллекции", monitor -> { //$NON-NLS-1$
            runFilterScan(filter, monitor, onDone);
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        filterJob.setSystem(true);
        filterJob.schedule();
    }

    void cancelFilterScan()
    {
        cancelFilterJob();
    }

    /** После смены схемы колонок — отменить текущий load, иначе батч пишет в старую модель. */
    void resetLoadJobForSchemaChange()
    {
        cancelJob(loadJob);
        loadPending.set(false);
    }

    void cancelAll()
    {
        disposed.set(true);
        shellVisible.set(false);
        removeShellStateListener();
        CollectionSizeResolver.cancelAll();
        cancelJob(loadJob);
        cancelJob(sizeJob);
        cancelJob(filterJob);
        cancelJob(contextJob);
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

    private boolean isShellActiveForLoad()
    {
        if (disposed.get())
            return false;
        if (shellMinimized.get())
            return false;
        return shellVisible.get();
    }

    private boolean isLoadJobBusy()
    {
        if (loadJob == null)
            return false;
        int state = loadJob.getState();
        return state == Job.RUNNING || state == Job.WAITING;
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
                context = CollectionContextColumnsResolver.resolve(model.indexedValue);
            }
            catch (DebugException e)
            {
                ComfortCollectionDebug.problem("contextVariables: " + e.getMessage()); //$NON-NLS-1$
            }
            final IBslVariable[] result = context != null ? context : new IBslVariable[0];
            if (result.length == 0 && !disposed.get() && !monitor.isCanceled()
                && contextResolveAttempts.incrementAndGet() <= 8)
            {
                Job followUp = Job.create("Комфорт: колонки коллекции (retry)", m -> { //$NON-NLS-1$
                    scheduleContextJobInternal();
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                });
                followUp.setSystem(true);
                followUp.schedule(200);
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
            if (result.length > 0)
                contextResolveAttempts.set(0);
            if (display != null && !display.isDisposed())
            {
                display.asyncExec(() -> {
                    if (!disposed.get())
                        contextColumnsListener.accept(result);
                });
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        contextJob.setSystem(true);
        contextJob.schedule();
    }

    private void scheduleSizeJob()
    {
        if (disposed.get())
            return;
        cancelJob(sizeJob);
        sizeJob = Job.create("Комфорт: размер коллекции", monitor -> { //$NON-NLS-1$
            try
            {
                int size = model.indexedValue.getSize();
                model.totalSize = size;
                fireProgress(0, size, "size"); //$NON-NLS-1$
            }
            catch (DebugException e)
            {
                ComfortCollectionDebug.problem("getSize: " + e.getMessage()); //$NON-NLS-1$
            }
            scheduleLoadJob("afterSize"); //$NON-NLS-1$
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        sizeJob.setSystem(true);
        sizeJob.schedule();
    }

    private void scheduleLoadJob(String reason)
    {
        if (disposed.get() || !isShellActiveForLoad())
        {
            ComfortCollectionDebug.step("load", "skip " + reason //$NON-NLS-1$ //$NON-NLS-2$
                + " vis=" + shellVisible.get() + " min=" + shellMinimized.get()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        loadPending.set(true);
        if (isLoadJobBusy())
            return;
        startLoadJob(reason);
    }

    private void startLoadJob(String reason)
    {
        cancelJob(loadJob);
        loadJob = Job.create("Комфорт: загрузка коллекции", monitor -> { //$NON-NLS-1$
            loadPending.set(false);
            if (disposed.get() || monitor.isCanceled() || !isShellActiveForLoad())
                return org.eclipse.core.runtime.Status.OK_STATUS;

            LoadBatchResult batch = runLoadBatch(monitor);
            boolean more = batch.more;
            int cellsWritten = batch.cellsWritten;

            if ((more || loadPending.get()) && !disposed.get() && isShellActiveForLoad() && loadJob != null)
            {
                int delay = cellsWritten > 0 ? 50 : 150;
                loadJob.schedule(delay);
                if (cellsWritten <= 0)
                    ComfortCollectionDebug.step("load", "deferred retry delay=" + delay); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        loadJob.setSystem(true);
        loadJob.schedule(0);
        ComfortCollectionDebug.step("load", reason); //$NON-NLS-1$
    }

    private static final class LoadBatchResult
    {
        final boolean more;
        final int cellsWritten;

        LoadBatchResult(boolean more, int cellsWritten)
        {
            this.more = more;
            this.cellsWritten = cellsWritten;
        }
    }

    private LoadBatchResult runLoadBatch(org.eclipse.core.runtime.IProgressMonitor monitor)
    {
        int total = model.totalSize;
        if (total < 0)
            total = Math.max(model.loadedRowCount + BATCH_SIZE, BATCH_SIZE);

        int colFrom = viewportColFrom.get();
        int colTo = viewportColTo.get();
        if (colTo < 0)
            colTo = Math.max(0, model.columns.columnCount() - 1);

        int browseBound = browseUpperBound(total);
        int from = resolveNextWorkRow(colFrom, colTo, total, browseBound);
        if (from < 0)
            return new LoadBatchResult(false, 0);

        int count = batchCount(from, total, browseBound);
        if (count <= 0)
            return new LoadBatchResult(hasMoreWork(colFrom, colTo, total, browseBound), 0);

        try
        {
            boolean needVars = false;
            for (int row = from; row < from + count; row++)
            {
                if (model.getRowVariable(row) == null)
                {
                    needVars = true;
                    break;
                }
            }
            if (needVars)
            {
                IBslVariable[] batch = model.indexedValue.getVariables(from, count);
                model.putRowVariables(from, batch);
            }
            int cellsWritten = fillCellsInBatch(from, count, colFrom, colTo);
            boolean more = hasMoreWork(colFrom, colTo, total, browseBound);
            if (cellsWritten <= 0)
            {
                ComfortCollectionDebug.step("load", //$NON-NLS-1$
                    "batch deferred rows=" + from + "+" + count //$NON-NLS-1$ //$NON-NLS-2$
                        + " cols=" + colFrom + ".." + colTo); //$NON-NLS-1$ //$NON-NLS-2$
                // #region agent log
                CollectionLoadDebug.log("H1,H4", "CollectionLoadScheduler.runLoadBatch", "zeroCells", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"from\":" + from + ",\"count\":" + count //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"colFrom\":" + colFrom + ",\"colTo\":" + colTo //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"more\":" + more + ",\"total\":" + total //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"browseBound\":" + browseBound + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
            }
            fireBrowseProgress(colFrom, colTo, browseBound, total);
            final int fFrom = from;
            final int fCount = count;
            CollectionSizeResolver.scheduleBatch(model, fFrom, fCount, colFrom, colTo, display,
                () -> fireRowsReady(fFrom, fCount));
            fireRowsReady(from, count);
            // #region agent log
            if (cellsWritten > 0)
                CollectionLoadDebug.log("H1", "CollectionLoadScheduler.runLoadBatch", "batch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"from\":" + from + ",\"count\":" + count //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"cellsWritten\":" + cellsWritten + ",\"more\":" + more + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            return new LoadBatchResult(more, cellsWritten);
        }
        catch (DebugException e)
        {
            ComfortCollectionDebug.problem("getVariables: " + e.getMessage()); //$NON-NLS-1$
            return new LoadBatchResult(hasMoreWork(colFrom, colTo, total, browseBound), 0);
        }
    }

    private static int browseUpperBound(int total)
    {
        if (total <= 0)
            return AUTO_LOAD_ROW_LIMIT;
        return Math.min(total, AUTO_LOAD_ROW_LIMIT);
    }

    private int resolveNextWorkRow(int colFrom, int colTo, int total, int browseBound)
    {
        int vpFirst = Math.max(0, viewportFirst.get());
        int vpLast = viewportLast.get();
        if (total > 0 && vpLast >= total)
            vpLast = total - 1;
        if (vpLast < vpFirst)
            vpLast = Math.min(vpFirst + BATCH_SIZE - 1, total > 0 ? total - 1 : vpFirst + BATCH_SIZE - 1);

        int from = findNextWork(vpFirst, vpLast, colFrom, colTo);
        if (from >= 0)
            return from;

        if (browseBound <= 0)
            return -1;
        return findNextWork(0, browseBound - 1, colFrom, colTo);
    }

    private boolean hasMoreWork(int colFrom, int colTo, int total, int browseBound)
    {
        return resolveNextWorkRow(colFrom, colTo, total, browseBound) >= 0;
    }

    private static int batchCount(int from, int total, int browseBound)
    {
        int upper = total > 0 ? total : from + BATCH_SIZE;
        int count = Math.min(BATCH_SIZE, upper - from);
        if (from < browseBound)
            count = Math.min(count, browseBound - from);
        return count;
    }

    private void fireBrowseProgress(int colFrom, int colTo, int browseBound, int total)
    {
        int loaded = model.countRowsFullyLoaded(0, Math.max(0, browseBound - 1), colFrom, colTo);
        int reportTotal = total > 0 ? total : browseBound;
        if (total > AUTO_LOAD_ROW_LIMIT)
            ComfortCollectionDebug.step("load", "prefetch " + loaded + "/" + total //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " autoLimit=" + AUTO_LOAD_ROW_LIMIT); //$NON-NLS-1$
        fireProgress(loaded, reportTotal, "rows"); //$NON-NLS-1$
    }

    private int fillCellsInBatch(int from, int count, int colFrom, int colTo) throws DebugException
    {
        colFrom = Math.max(0, colFrom);
        colTo = Math.min(colTo, model.columns.columnCount() - 1);
        int written = 0;
        for (int row = from; row < from + count; row++)
        {
            for (int col = colFrom; col <= colTo; col++)
            {
                String text = model.extractCellTextInJob(row, col);
                if (text != null)
                {
                    model.setCellText(row, col, text);
                    written++;
                }
            }
        }
        return written;
    }

    private int findNextWork(int first, int last, int colFrom, int colTo)
    {
        if (last < first)
            return -1;
        for (int i = first; i <= last; i++)
        {
            if (model.needsRowLoad(i, colFrom, colTo))
                return i;
        }
        return -1;
    }

    private void runFilterScan(CollectionRowFilter filter, org.eclipse.core.runtime.IProgressMonitor monitor, Runnable onDone)
    {
        int total = model.totalSize;
        if (total <= 0)
        {
            asyncDone(onDone);
            return;
        }
        java.util.BitSet matches = new java.util.BitSet(total);
        int processed = 0;
        for (int from = 0; from < total; from += BATCH_SIZE)
        {
            if (monitor.isCanceled() || disposed.get() || filter.isCancelled())
                break;
            int count = Math.min(BATCH_SIZE, total - from);
            try
            {
                IBslVariable[] batch = model.indexedValue.getVariables(from, count);
                model.putRowVariables(from, batch);
                for (int i = 0; i < count; i++)
                {
                    int row = from + i;
                    String text = model.rowFilterText(row);
                    if (filter.matcher().matches(text))
                        matches.set(row);
                }
            }
            catch (DebugException e)
            {
                ComfortCollectionDebug.problem("filter scan: " + e.getMessage()); //$NON-NLS-1$
            }
            processed = from + count;
            fireProgress(processed, total, "filter"); //$NON-NLS-1$
            filter.setProgress(processed, total, matches);
        }
        if (!filter.isCancelled())
            filter.finishScan(matches);
        asyncDone(onDone);
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

    private void cancelFilterJob()
    {
        cancelJob(filterJob);
    }
}
