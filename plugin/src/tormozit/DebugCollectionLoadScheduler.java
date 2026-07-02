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
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Фоновая подгрузка строк коллекции micro-порциями; UI thread не блокируется.
 */
final class DebugCollectionLoadScheduler
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

    private final DebugCollectionTableModel model;
    private final Display display;
    private final ProgressListener progress;
    private final Consumer<IBslVariable[]> contextColumnsListener;
    private Table tableForViewport;
    private Shell shellForPause;
    private final java.util.concurrent.atomic.AtomicBoolean disposed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger viewportFirst = new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger viewportLast = new java.util.concurrent.atomic.AtomicInteger(-1);
    /** Диапазон колонок для pass 1 (текст) — все видимые в схеме. */
    private final java.util.concurrent.atomic.AtomicInteger viewportColFrom = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger viewportColTo = new java.util.concurrent.atomic.AtomicInteger(-1);
    /** Pass 2: `[N]` только для ячеек, видимых на экране — снимок с UI thread. */
    private final java.util.concurrent.atomic.AtomicInteger sizeRowFrom = new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizeRowTo = new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicInteger sizeColFrom = new java.util.concurrent.atomic.AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicInteger sizeColTo = new java.util.concurrent.atomic.AtomicInteger(-1);
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
    private final java.util.concurrent.atomic.AtomicInteger sizePassGeneration =
        new java.util.concurrent.atomic.AtomicInteger();
    /** Инкремент при смене схемы — устаревшие батчи не трогают UI. */
    private final java.util.concurrent.atomic.AtomicInteger loadGeneration =
        new java.util.concurrent.atomic.AtomicInteger();

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
        DebugCollectionStringFormatResolver.resetForNewWindow();
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
        captureSizeViewport(0, BATCH_SIZE + OVERSCAN, tableForViewport);
        scheduleContextJob();
        scheduleSizeJob();
        scheduleLoadJob("initial"); //$NON-NLS-1$
    }

    /** Клон окна: схема и размер уже в модели — догрузка только пробелов viewport. */
    void scheduleFromClone(int logicalFirst, int logicalLast)
    {
        updateShellActiveSnapshot();
        int first = Math.max(0, logicalFirst - OVERSCAN);
        int last = logicalLast + OVERSCAN;
        viewportFirst.set(first);
        viewportLast.set(last);
        captureColumnViewport();
        captureSizeViewport(first, last, tableForViewport);
        if (model.totalSize < 0)
            scheduleSizeJob();
        else
            fireProgress(model.loadedRowCount, model.totalSize, "rows"); //$NON-NLS-1$
        scheduleLoadJob("clone"); //$NON-NLS-1$
        scheduleSizePass();
    }

    void requestViewport(int first, int last)
    {
        if (disposed.get() || !isShellActiveForLoad())
            return;
        int logicalFirst = Math.max(0, first - OVERSCAN);
        int logicalLast = last + OVERSCAN;
        viewportFirst.set(logicalFirst);
        viewportLast.set(logicalLast);
        captureColumnViewport();
        captureSizeViewport(logicalFirst, logicalLast, tableForViewport);
        scheduleLoadJob("viewport"); //$NON-NLS-1$
        scheduleSizePass();
    }

    /** Pass 1: все колонки схемы — свойства строки приходят пачкой из getVariables. */
    void captureColumnViewport()
    {
        int maxCol = Math.max(0, model.columns.columnCount() - 1);
        viewportColFrom.set(0);
        viewportColTo.set(maxCol);
        DebugCollectionDebug.step("load", "cols=0.." + maxCol); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Pass 2: logical rows на экране + горизонтально видимые model-колонки data-таблицы. */
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
        int[] cols = DebugCollectionViewportTracker.visibleModelColumnRange(
            dataTable, model.columns.columnCount(), model.columns.fixedColumnCount());
        sizeColFrom.set(Math.min(maxCol, Math.max(1, cols[0])));
        sizeColTo.set(Math.min(maxCol, cols[1]));
    }

    /** Pass 2: getSize для вложенных коллекций — только size viewport. */
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
        return loadPending.get() || isLoadJobBusy();
    }

    void scheduleFilterScan(DebugCollectionRowFilter filter, Runnable onDone)
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
        loadGeneration.incrementAndGet();
        cancelJob(loadJob);
        loadPending.set(false);
    }

    void cancelAll()
    {
        disposed.set(true);
        shellVisible.set(false);
        sizePassGeneration.incrementAndGet();
        removeShellStateListener();
        DebugCollectionSizeResolver.cancelAll();
        DebugCollectionStringFormatResolver.cancelAll();
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

    private boolean isLoadJobBusy() {
        if (loadJob == null) return false;
        int state = loadJob.getState();
        return state == Job.RUNNING || state == Job.WAITING || state == Job.SLEEPING;
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
                && !monitor.isCanceled() && contextResolveAttempts.incrementAndGet() <= 8)
            {
                if (metadataOnly)
                    DebugCollectionDebug.step("columns.ctx", "metadata-retry attempt=" //$NON-NLS-1$ //$NON-NLS-2$
                        + contextResolveAttempts.get());
                else if (indexedPlaceholders)
                    DebugCollectionDebug.step("columns.ctx", "placeholder-retry attempt=" //$NON-NLS-1$ //$NON-NLS-2$
                        + contextResolveAttempts.get());
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
                if (size == 0)
                    fireProgress(0, 0, "rows"); //$NON-NLS-1$
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("getSize: " + e.getMessage()); //$NON-NLS-1$
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
            DebugCollectionDebug.step("load", "skip " + reason //$NON-NLS-1$ //$NON-NLS-2$
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

            // canRetry: перезапускаемся если есть ещё работа, даже если все ячейки в PLACEHOLDER (pending)
            boolean canRetry = model.totalSize != 0 
                && (more || loadPending.get());
            if (canRetry && !disposed.get() && isShellActiveForLoad() && loadJob != null)
            {
                int delay = cellsWritten > 0 ? 50 : 500; // Большая задержка если все PLACEHOLDER — дать evaluate время
                loadJob.schedule(delay);
                if (cellsWritten <= 0)
                    DebugCollectionDebug.step("load", "deferred retry delay=" + delay); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        loadJob.setSystem(true);
        loadJob.schedule(0);
        DebugCollectionDebug.step("load", reason); //$NON-NLS-1$
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
        final int batchGeneration = loadGeneration.get();
        int total = model.totalSize;
        if (total < 0)
            return new LoadBatchResult(false, 0);
        if (total == 0)
            return new LoadBatchResult(false, 0);

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
            if (isAutoPrefetchComplete() && from < browseBound)
                more = false;
            if (cellsWritten <= 0)
            {
                DebugCollectionDebug.step("load", //$NON-NLS-1$
                    "batch deferred rows=" + from + "+" + count //$NON-NLS-1$ //$NON-NLS-2$
                        + " cols=" + colFrom + ".." + colTo); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (batchGeneration != loadGeneration.get())
                return new LoadBatchResult(hasMoreWork(colFrom, colTo, total, browseBound), 0);

            fireBrowseProgress(colFrom, colTo, browseBound, total);
            if (cellsWritten > 0 && batchIntersectsRowViewport(from, count))
                fireRowsReady(from, count);
            if (cellsWritten > 0)
            {
                final int formatFrom = from;
                final int formatCount = count;
                DebugCollectionStringFormatResolver.scheduleBatch(model, from, count, colFrom, colTo, display,
                    () -> {
                        if (batchIntersectsRowViewport(formatFrom, formatCount))
                            fireRowsReady(formatFrom, formatCount);
                    });
            }
            if (cellsWritten > 0 && batchIntersectsSizeViewport(from, count))
                scheduleSizePass();
            return new LoadBatchResult(more, cellsWritten);
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("getVariables: " + e.getMessage()); //$NON-NLS-1$
            return new LoadBatchResult(hasMoreWork(colFrom, colTo, total, browseBound), 0);
        }
    }

    private boolean batchIntersectsRowViewport(int batchFrom, int batchCount)
    {
        int vpFirst = Math.max(0, viewportFirst.get());
        int vpLast = viewportLast.get();
        int total = model.totalSize;
        if (total > 0 && vpLast >= total)
            vpLast = total - 1;
        if (vpLast < vpFirst || batchCount <= 0)
            return false;
        int batchTo = batchFrom + batchCount - 1;
        return batchFrom <= vpLast && batchTo >= vpFirst;
    }

    private boolean batchIntersectsSizeViewport(int batchFrom, int batchCount)
    {
        int sizeFrom = sizeRowFrom.get();
        int sizeTo = sizeRowTo.get();
        if (sizeFrom < 0 || sizeTo < sizeFrom || batchCount <= 0)
            return false;
        int batchTo = batchFrom + batchCount - 1;
        return batchFrom <= sizeTo && batchTo >= sizeFrom;
    }

    private static int browseUpperBound(int total)
    {
        if (total <= 0)
            return 0;
        return Math.min(total, AUTO_LOAD_ROW_LIMIT);
    }

    /** Автоподгрузка 0..min(total, {@link #AUTO_LOAD_ROW_LIMIT}) — все ячейки схемы заполнены. */
    boolean isAutoPrefetchComplete()
    {
        if (disposed.get() || model.columns.columnCount() <= 0)
            return false;
        int total = model.totalSize;
        if (total == 0)
            return true;
        int browseBound = browseUpperBound(total);
        if (browseBound <= 0)
            return false;
        int colTo = Math.max(0, model.columns.columnCount() - 1);
        int loaded = model.countRowsFullyLoaded(0, browseBound - 1, 0, colTo);
        return loaded >= browseBound;
    }

    private int resolveNextWorkRow(int colFrom, int colTo, int total, int browseBound)
    {
        if (total == 0)
            return -1;
        if (browseBound <= 0)
            return -1;

        int vpFirst = Math.max(0, viewportFirst.get());
        int vpLast = viewportLast.get();
        if (total > 0 && vpLast >= total)
            vpLast = total - 1;
        if (vpLast < vpFirst)
            vpLast = Math.min(vpFirst + BATCH_SIZE - 1, total > 0 ? total - 1 : vpFirst + BATCH_SIZE - 1);

        int vpWork = findNextWork(vpFirst, vpLast, colFrom, colTo);
        int frontier = Math.min(browseBound, model.loadedRowCount);
        int beyondWork = frontier < browseBound
            ? findNextWork(frontier, browseBound - 1, colFrom, colTo)
            : -1;

        // Не зацикливаться на viewport 0..N, пока дальше по коллекции ещё нет переменных/ячеек.
        if (beyondWork >= 0 && (vpWork < 0 || beyondWork >= frontier && vpWork < frontier))
            return beyondWork;
        if (vpWork >= 0)
            return vpWork;

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
            DebugCollectionDebug.step("load", "prefetch " + loaded + "/" + total //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " autoLimit=" + AUTO_LOAD_ROW_LIMIT); //$NON-NLS-1$
        fireProgress(loaded, reportTotal, "rows"); //$NON-NLS-1$
    }

    private int fillCellsInBatch(int from, int count, int colFrom, int colTo) throws DebugException
    {
        colFrom = Math.max(0, colFrom);
        colTo = Math.min(colTo, model.columns.columnCount() - 1);

        // Round 1: собственные значения строк одним комбинированным запросом вместо N отдельных evaluate().
        List<IBslValue> rowValues = model.collectRowValuesForBatchEvaluate(from, count);
        if (!rowValues.isEmpty())
            DebugCollectionBatchEvaluator.evaluateBatch(model.frame, rowValues);

        // Round 2: property-дети видимых колонок — только после Round 1 (свойства строки уже
        // должны быть перечислимы, иначе rowPropertySource вернёт null).
        List<IBslValue> propValues = model.collectPropertyValuesForBatchEvaluate(from, count, colFrom, colTo);
        if (!propValues.isEmpty())
            DebugCollectionBatchEvaluator.evaluateBatch(model.frame, propValues);

        int written = 0;
        int placeholders = 0;
        for (int row = from; row < from + count; row++)
        {
            for (int col = colFrom; col <= colTo; col++)
            {
                if (model.isCellFilled(row, col))
                    continue;
                String text = model.extractCellTextInJob(row, col);
                if (text == null)
                {
                    text = DebugCollectionTableModel.PLACEHOLDER;
                    placeholders++;
                }
                model.setCellText(row, col, text);
                if (!DebugCollectionTableModel.PLACEHOLDER.equals(text))
                    written++;
            }
        }
        DebugCollectionDebug.step("fillBatch", "from=" + from + " count=" + count + " written=" + written + " placeholders=" + placeholders);
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

    private void runFilterScan(DebugCollectionRowFilter filter, org.eclipse.core.runtime.IProgressMonitor monitor, Runnable onDone)
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
                    String text = model.rowFilterText(row, filter.isPresentationOnly());
                    if (filter.matcher().matches(text))
                        matches.set(row);
                }
            }
            catch (DebugException e)
            {
                DebugCollectionDebug.problem("filter scan: " + e.getMessage()); //$NON-NLS-1$
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

    /**
     * Колонки property: union имён из строк (как EDT {@code getMutualProperties}) или
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

            IBslVariable[] rowUnion = resolveRowSampleUnion(indexed);
            IBslVariable[] direct = indexed.getContextVariables();
            if (direct == null)
                direct = new IBslVariable[0];

            if (DebugCollectionPropertyVariables.isAcceptableColumnContext(rowUnion))
            {
                IBslVariable[] chosen = capColumns(rowUnion);
                DebugCollectionDebug.step("columns.ctx", //$NON-NLS-1$
                    "rowUnion=" + rowUnion.length //$NON-NLS-1$
                        + (direct.length > 0 ? " direct=" + direct.length : "")); //$NON-NLS-1$ //$NON-NLS-2$
                return chosen;
            }

            if (isTabularCollectionType(indexed))
            {
                IBslVariable[] tableSchema = resolveValueTableSchemaColumns(indexed);
                if (DebugCollectionPropertyVariables.isAcceptableColumnContext(tableSchema))
                {
                    IBslVariable[] chosen = capColumns(tableSchema);
                    DebugCollectionDebug.step("columns.ctx", "tableSchema=" + tableSchema.length); //$NON-NLS-1$ //$NON-NLS-2$
                    return chosen;
                }
            }

            IBslVariable[] rowVars = resolveRowVariablesUnion(indexed);
            if (DebugCollectionPropertyVariables.isAcceptableColumnContext(rowVars))
            {
                IBslVariable[] chosen = capColumns(rowVars);
                DebugCollectionDebug.step("columns.ctx", "rowVars=" + rowVars.length); //$NON-NLS-1$ //$NON-NLS-2$
                return chosen;
            }

            if (direct.length > 0 && direct.length <= MAX_DIRECT_CONTEXT_COLS
                && DebugCollectionPropertyVariables.isAcceptableColumnContext(direct))
            {
                DebugCollectionDebug.step("columns.ctx", "direct=" + direct.length); //$NON-NLS-1$ //$NON-NLS-2$
                return direct;
            }

            if (direct.length > MAX_DIRECT_CONTEXT_COLS)
            {
                IBslVariable[] union = capColumns(direct);
                DebugCollectionDebug.step("columns.ctx", //$NON-NLS-1$
                    "direct=" + direct.length + " wide→directUnion=" + union.length); //$NON-NLS-1$ //$NON-NLS-2$
                return union;
            }

            IBslVariable[] mutual = resolveMutualFromRows(indexed);
            if (DebugCollectionPropertyVariables.isAcceptableColumnContext(mutual))
            {
                DebugCollectionDebug.step("columns.ctx", "mutual=" + mutual.length); //$NON-NLS-1$ //$NON-NLS-2$
                return mutual;
            }

            if (direct.length > 0 && DebugCollectionPropertyVariables.isAcceptableColumnContext(direct))
            {
                DebugCollectionDebug.step("columns.ctx", "directFallback=" + direct.length); //$NON-NLS-1$ //$NON-NLS-2$
                return capColumns(direct);
            }
            return new IBslVariable[0];
        }

        /** Union property-имён из первых строк — как EDT {@code getMutualProperties}. */
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
                if (value == null || value.isPending())
                    continue;
                if (!value.isEvaluated())
                    value.evaluate();
                if (value.isPending())
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

        /** Union имён из {@code getVariables()} строк — поля данных ТаблицаЗначений и др. */
        private static IBslVariable[] resolveRowVariablesUnion(IBslIndexedValue indexed) throws DebugException
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
                if (value == null || value.isPending())
                    continue;
                if (!value.isEvaluated())
                    value.evaluate();
                if (value.isPending())
                    continue;

                IBslVariable[] props = value.getVariables();
                if (props == null || props.length == 0)
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
            if (columnsValue == null || columnsValue.isPending())
                return new IBslVariable[0];
            if (!columnsValue.isEvaluated())
                columnsValue.evaluate();
            if (columnsValue.isPending())
                return new IBslVariable[0];

            if (columnsValue instanceof IBslIndexedValue columnsIndexed)
            {
                int size = columnsIndexed.getSize();
                if (size <= 0)
                    return new IBslVariable[0];
                IBslVariable[] defs = columnsIndexed.getVariables(0, size);
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
                "directUnion capped " + direct.length + "→" + MAX_COLUMN_COUNT); //$NON-NLS-1$ //$NON-NLS-2$
            return Arrays.copyOf(direct, MAX_COLUMN_COUNT);
        }

        private static IBslVariable[] resolveMutualFromRows(IBslIndexedValue indexed) throws DebugException
        {
            int size = indexed.getSize();
            if (size <= 0)
                return new IBslVariable[0];

            int count = Math.min(size, SAMPLE_ROWS);
            IBslVariable[] rows = indexed.getVariables(0, count);
            if (rows == null || rows.length == 0)
                return new IBslVariable[0];

            Set<String> mutual = null;
            Map<String, IBslVariable> templates = new LinkedHashMap<>();
            for (IBslVariable row : rows)
            {
                Set<String> rowNames = new LinkedHashSet<>();
                collectPropertyNames(row, rowNames, templates);
                if (rowNames.isEmpty())
                    continue;
                if (mutual == null)
                    mutual = new LinkedHashSet<>(rowNames);
                else
                    mutual.retainAll(rowNames);
            }

            if (mutual == null || mutual.isEmpty())
                return new IBslVariable[0];

            List<IBslVariable> result = new ArrayList<>();
            for (String name : mutual)
            {
                IBslVariable template = templates.get(name);
                if (template != null)
                    result.add(template);
            }
            DebugCollectionDebug.step("columns.ctx", "mutualRows=" + rows.length); //$NON-NLS-1$ //$NON-NLS-2$
            return result.toArray(new IBslVariable[0]);
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
