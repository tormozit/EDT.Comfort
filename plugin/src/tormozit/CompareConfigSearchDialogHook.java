package tormozit;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartService;
import org.eclipse.ui.IWorkbenchPart;

import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractDirectPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractNodeWithLabels;

/**
 * Патч диалога поиска EDT (CTRL+F в дереве сравнения конфигураций).
 */
public class CompareConfigSearchDialogHook
{
    private static final String PATCHED_KEY = "tormozit.searchPatched"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.compareSearchSession"; //$NON-NLS-1$
    private static final String DIALOG_CLASS = "ComparisonTreeSearchDialog"; //$NON-NLS-1$
    private static final int PROGRESS_INTERVAL_MS = 2000;
    private static final int COLLECT_CANCEL_CHECK_INTERVAL = 256;

    // Ключи настроек для хранения в dialog_settings.xml
    private static final String SETTINGS_SECTION = "TormozitCompareConfigSearchSettings"; //$NON-NLS-1$
    private static final String KEY_SEARCH_All_rows = "searchAllRows"; //$NON-NLS-1$
    private static final String KEY_SEARCH_All_columns = "searchAllColumns"; //$NON-NLS-1$

    public static void install(Display display)
    {
        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell)event.widget;
            if (shell.getData(PATCHED_KEY) != null)
                return;

            Object dialog = shell.getData();
            if (dialog == null)
                return;
            if (!dialog.getClass().getName().contains(DIALOG_CLASS))
                return;

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            patchDialog(shell, dialog);
        });
    }

    private static void patchDialog(Shell shell, Object dialog)
    {
        Button btnCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
        Button btnNext = (Button)getField(dialog, "buttonSearch"); //$NON-NLS-1$
        Button btnPrev = (Button)getField(dialog, "buttonSearchBack"); //$NON-NLS-1$

        if (btnNext == null || btnPrev == null)
            return;

        Composite parent = btnCase != null ? btnCase.getParent() : btnNext.getParent();

        IDialogSettings settings = getDialogSettings();

        Button cbSearchAllRows = new Button(parent, SWT.CHECK);
        cbSearchAllRows.setText("По всем строкам");
        cbSearchAllRows.setToolTipText("Стандартный поиск EDT ищет только по строкам имен объектов. Этот флажок включает просмотр всех строк" + Global.pluginSignForTooltip());
        cbSearchAllRows.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

        boolean loadDetailed = settings.get(KEY_SEARCH_All_rows) == null ? true : settings.getBoolean(KEY_SEARCH_All_rows);
        cbSearchAllRows.setSelection(loadDetailed);

        cbSearchAllRows.addListener(SWT.Selection, event -> {
            settings.put(KEY_SEARCH_All_rows, cbSearchAllRows.getSelection());
        });

        if (btnCase != null)
            cbSearchAllRows.moveBelow(btnCase);

        Button cbSearchAllColumns = new Button(parent, SWT.CHECK);
        cbSearchAllColumns.setText("По всем колонкам");
        cbSearchAllColumns.setToolTipText("Дополнительно к поиску в колонках значений еще искать в колонке \"Объект\"" + Global.pluginSignForTooltip());
        cbSearchAllColumns.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

        boolean loadObjectCol = settings.get(KEY_SEARCH_All_columns) == null ? true : settings.getBoolean(KEY_SEARCH_All_columns);
        cbSearchAllColumns.setSelection(loadObjectCol);

        cbSearchAllColumns.addListener(SWT.Selection, event -> {
            settings.put(KEY_SEARCH_All_columns, cbSearchAllColumns.getSelection());
        });

        if (btnCase != null)
            cbSearchAllColumns.moveBelow(btnCase);

        CompareConfigSearchSession session = new CompareConfigSearchSession(shell, dialog, btnNext, btnPrev);
        shell.setData(SESSION_KEY, session);
        shell.addListener(SWT.Close, event -> session.onShellClose());

        interceptButton(btnNext, cbSearchAllRows, session, false, cbSearchAllColumns);
        interceptButton(btnPrev, cbSearchAllRows, session, true, cbSearchAllColumns);

        parent.layout(true, true);
        shell.pack();
    }

    private static IDialogSettings getDialogSettings()
    {
        IDialogSettings topSettings = Activator.getDefault().getDialogSettings();
        IDialogSettings section = topSettings.getSection(SETTINGS_SECTION);
        if (section == null)
        {
            section = topSettings.addNewSection(SETTINGS_SECTION);
        }
        return section;
    }

    private static void interceptButton(Button button, Button cbSearchAllRows, CompareConfigSearchSession session, boolean backward, Button cbSearchAllColumns)
    {
        Listener[] original = button.getListeners(SWT.Selection);
        for (Listener l : original)
            button.removeListener(SWT.Selection, l);

        button.addListener(SWT.Selection, event ->
        {
            if (!cbSearchAllRows.getSelection())
            {
                for (Listener l : original)
                    l.handleEvent(event);
            }
            else
            {
                if (session.isRunning())
                    return;
                session.startSearch(backward, cbSearchAllColumns.getSelection());
            }
        });
    }

    private static int computeTreeCacheKey(AbstractTreeViewer viewer, Object input)
    {
        return Objects.hash(Arrays.hashCode(viewer.getFilters()), System.identityHashCode(input));
    }

    public static boolean isNodeMatchFilters(
        Object element,
        AbstractTreeViewer viewer)
    {
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (!filter.select(viewer, null, element))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean collectModelItems(
        Object parent,
        ITreeContentProvider provider,
        List<Object> result,
        AbstractTreeViewer viewer,
        CollectProgress progress)
    {
        Object[] children = provider.getChildren(parent);
        if (children != null)
        {
            for (Object child : children)
            {
                if (!progress.tick())
                    return false;
                if (isNodeMatchFilters(child, viewer))
                {
                    result.add(child);
                    progress.nodeAdded();
                }
                if (!collectModelItems(child, provider, result, viewer, progress))
                    return false;
            }
        }
        return true;
    }

    private static final class CollectProgress
    {
        private final int generation;
        private final IntSupplier activeGeneration;
        private final IProgressMonitor monitor;
        private final Runnable onNodeAdded;
        private int nodesSinceCheck;

        CollectProgress(int generation, IntSupplier activeGeneration, IProgressMonitor monitor, Runnable onNodeAdded)
        {
            this.generation = generation;
            this.activeGeneration = activeGeneration;
            this.monitor = monitor;
            this.onNodeAdded = onNodeAdded;
        }

        boolean tick()
        {
            if (++nodesSinceCheck < COLLECT_CANCEL_CHECK_INTERVAL)
                return true;
            nodesSinceCheck = 0;
            return !monitor.isCanceled() && generation == activeGeneration.getAsInt();
        }

        void nodeAdded()
        {
            onNodeAdded.run();
        }
    }

    private static boolean isMatched(Object element, String query, boolean caseSensitive, IBaseLabelProvider baseLabelProvider, boolean cbSearchAllColumns)
    {
        if (baseLabelProvider instanceof ILabelProvider) {
            String text;
            if (element instanceof AbstractNodeWithLabels) {
                AbstractNodeWithLabels node = (AbstractNodeWithLabels) element;
                text = node.getSideLabel(ComparisonSide.MAIN);
                if (text != null && (caseSensitive ? text.contains(query) : text.toLowerCase().contains(query)))
                {
                    return true;
                }
                text = node.getSideLabel(ComparisonSide.OTHER);
                if (text != null && (caseSensitive ? text.contains(query) : text.toLowerCase().contains(query)))
                {
                    return true;
                }
                text = node.getSideLabel(ComparisonSide.COMMON_ANCESTOR);
                if (text != null && (caseSensitive ? text.contains(query) : text.toLowerCase().contains(query)))
                {
                    return true;
                }
            }
            if (cbSearchAllColumns && element instanceof AbstractDirectPartialModelNode) {
                AbstractDirectPartialModelNode node = (AbstractDirectPartialModelNode) element;
                text = node.getLabel();
                if (text != null && (caseSensitive ? text.contains(query) : text.toLowerCase().contains(query)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static void setStatus(Object dialog, String message)
    {
        try
        {
            dialog.getClass().getMethod("setMessage", String.class).invoke(dialog, message); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }

    private static void clearStatus(Object dialog)
    {
        setStatus(dialog, ""); //$NON-NLS-1$
    }

    private static void updateDialog(Object dialog)
    {
        try
        {
            dialog.getClass().getMethod("update").invoke(dialog); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }

    private static AbstractTreeViewer getTreeViewerFromDialog(Object dialog)
    {
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null) return null;

        IPartService ps = (IPartService)getField(controller, "partService"); //$NON-NLS-1$
        if (ps == null) return null;

        IWorkbenchPart part = ps.getActivePart();
        if (!(part instanceof IEditorPart)) return null;

        return getTreeViewerFromEditor((IEditorPart)part);
    }

    private static AbstractTreeViewer getTreeViewerFromEditor(IEditorPart editor)
    {
        Object view = getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView)) return null;

        Object treeControl = ((DtComparisonView)view).getTreeControl();
        if (treeControl == null) return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return (viewer instanceof AbstractTreeViewer) ? (AbstractTreeViewer)viewer : null;
    }

    private static Object getField(Object obj, String name)
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            try
            {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            catch (Exception ignored) { return null; }
        }
        return null;
    }

    private static Object invokeNoArg(Object o, String name)
    {
        if (o == null) return null;
        try { return o.getClass().getMethod(name).invoke(o); }
        catch (Exception ignored) { return null; }
    }

    /**
     * Фоновый прерываемый поиск по всему дереву сравнения (флажок «По всем строкам»).
     */
    private static final class CompareConfigSearchSession
    {
        private final Shell shell;
        private final Object dialog;
        private final Button btnNext;
        private final Button btnPrev;

        private volatile Job activeJob;
        private volatile int scanned;
        private volatile int total;
        private volatile int collected;
        private volatile boolean collecting;
        private volatile boolean running;
        private int activeGeneration;
        private Runnable progressTick;

        private List<Object> cachedItems;
        private Object cachedInput;
        private int cachedFilterHash;

        CompareConfigSearchSession(Shell shell, Object dialog, Button btnNext, Button btnPrev)
        {
            this.shell = shell;
            this.dialog = dialog;
            this.btnNext = btnNext;
            this.btnPrev = btnPrev;
        }

        boolean isRunning()
        {
            return running;
        }

        void onShellClose()
        {
            cancel();
            invalidateCache();
        }

        void cancel()
        {
            stopProgressTimer();
            Job job = activeJob;
            activeJob = null;
            if (job != null)
                job.cancel();
            if (running)
            {
                running = false;
                activeGeneration++;
            }
        }

        private void invalidateCache()
        {
            cachedItems = null;
            cachedInput = null;
            cachedFilterHash = 0;
        }

        private boolean isCacheValid(Object input, int filterHash)
        {
            return cachedItems != null && cachedInput == input && cachedFilterHash == filterHash;
        }

        private void saveCache(Object input, int filterHash, List<Object> items)
        {
            cachedInput = input;
            cachedFilterHash = filterHash;
            cachedItems = items;
        }

        void startSearch(boolean backward, boolean searchAllColumns)
        {
            cancel();
            activeGeneration++;

            Text textFilter = (Text)getField(dialog, "textFilter"); //$NON-NLS-1$
            if (textFilter == null || textFilter.isDisposed())
                return;

            String query = textFilter.getText();
            if (query.isEmpty())
                return;

            Button cbCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
            boolean caseSensitive = cbCase != null && cbCase.getSelection();
            String effectiveQuery = caseSensitive ? query : query.toLowerCase();

            AbstractTreeViewer viewer = getTreeViewerFromDialog(dialog);
            if (viewer == null)
                return;

            IContentProvider cp = viewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider))
                return;
            ITreeContentProvider provider = (ITreeContentProvider) cp;

            IBaseLabelProvider labelProvider = viewer.getLabelProvider();
            ISelection sel = viewer.getSelection();
            Object input = viewer.getInput();
            int filterHash = computeTreeCacheKey(viewer, input);
            boolean cacheHit = isCacheValid(input, filterHash);

            final int generation = activeGeneration;
            running = true;
            collecting = !cacheHit;
            scanned = 0;
            total = cacheHit ? cachedItems.size() : 0;
            collected = 0;

            setSearchButtonsEnabled(false);
            startProgressTimer(generation);

            if (Global.isLogEnabled())
                Global.log("CompareSearch", "start query=\"" + query + "\" backward=" + backward + " allColumns=" + searchAllColumns + " cacheHit=" + cacheHit); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            Job job = new Job("Поиск по дереву сравнения...") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    if (generation != activeGeneration)
                        return Status.CANCEL_STATUS;

                    List<Object> items;
                    if (cacheHit)
                    {
                        items = cachedItems;
                    }
                    else
                    {
                        long t0 = System.currentTimeMillis();
                        items = new ArrayList<>();
                        CollectProgress progress = new CollectProgress(
                            generation,
                            () -> activeGeneration,
                            monitor,
                            () -> collected++);

                        Object[] roots = provider.getElements(input);
                        if (roots != null)
                        {
                            for (Object root : roots)
                            {
                                if (!progress.tick())
                                    return finishCancelled(generation);
                                items.add(root);
                                progress.nodeAdded();
                                if (!collectModelItems(root, provider, items, viewer, progress))
                                    return finishCancelled(generation);
                            }
                        }
                        long collectMs = System.currentTimeMillis() - t0;

                        if (Global.isLogEnabled())
                            Global.log("CompareSearch", "collected " + items.size() + " items in " + collectMs + "ms"); //$NON-NLS-1$ //$NON-NLS-2$

                        saveCache(input, filterHash, items);
                        collecting = false;
                    }

                    int n = items.size();
                    total = n;
                    scanned = 0;

                    if (n <= 0)
                    {
                        finishOnUi(generation, false, false);
                        return Status.OK_STATUS;
                    }

                    int startIdx = (sel instanceof IStructuredSelection && !sel.isEmpty())
                        ? items.indexOf(((IStructuredSelection) sel).getFirstElement())
                        : -1;

                    monitor.beginTask("Поиск по дереву сравнения...", n); //$NON-NLS-1$

                    for (int i = 1; i <= n; i++)
                    {
                        if (monitor.isCanceled() || generation != activeGeneration)
                            return finishCancelled(generation);

                        int idx = backward
                            ? Math.floorMod(startIdx - i, n)
                            : (startIdx + i) % n;
                        Object candidate = items.get(idx);

                        if (isMatched(candidate, effectiveQuery, caseSensitive, labelProvider, searchAllColumns))
                        {
                            Object found = candidate;
                            Display.getDefault().asyncExec(() ->
                            {
                                if (generation != activeGeneration || textFilter.isDisposed())
                                    return;
                                viewer.setSelection(new StructuredSelection(found), true);
                                finishOnUi(generation, false, true);
                            });

                            if (Global.isLogEnabled())
                                Global.log("CompareSearch", "found at i=" + i + " idx=" + idx); //$NON-NLS-1$ //$NON-NLS-2$

                            monitor.done();
                            return Status.OK_STATUS;
                        }

                        scanned = i;

                        if (Global.isLogEnabled() && i % 500 == 0)
                            Global.log("CompareSearch", "progress " + i + "/" + n); //$NON-NLS-1$ //$NON-NLS-2$

                        monitor.worked(1);
                    }

                    if (Global.isLogEnabled())
                        Global.log("CompareSearch", "no match after " + n + " items"); //$NON-NLS-1$

                    finishOnUi(generation, false, false);
                    monitor.done();
                    return Status.OK_STATUS;
                }
            };
            activeJob = job;
            job.setSystem(true);
            job.schedule();
        }

        private IStatus finishCancelled(int generation)
        {
            if (Global.isLogEnabled())
                Global.log("CompareSearch", "cancelled"); //$NON-NLS-1$
            finishOnUi(generation, true, false);
            return Status.CANCEL_STATUS;
        }

        private void finishOnUi(int generation, boolean cancelled, boolean found)
        {
            Display display = shell != null && !shell.isDisposed() ? shell.getDisplay() : null;
            if (display == null || display.isDisposed())
            {
                running = false;
                activeJob = null;
                stopProgressTimer();
                return;
            }
            display.asyncExec(() ->
            {
                if (generation != activeGeneration)
                    return;
                stopProgressTimer();
                activeJob = null;
                running = false;
                if (shell.isDisposed())
                    return;
                if (cancelled)
                    clearStatus(dialog);
                else if (found)
                    setStatus(dialog, "Найдено"); //$NON-NLS-1$
                else
                    setStatus(dialog, "Не найдено"); //$NON-NLS-1$
                updateDialog(dialog);
            });
        }

        private void setSearchButtonsEnabled(boolean enabled)
        {
            if (btnNext != null && !btnNext.isDisposed())
                btnNext.setEnabled(enabled);
            if (btnPrev != null && !btnPrev.isDisposed())
                btnPrev.setEnabled(enabled);
        }

        private void startProgressTimer(int generation)
        {
            stopProgressTimer();
            Display display = shell.getDisplay();
            progressTick = () ->
            {
                if (generation != activeGeneration || !running || shell.isDisposed())
                    return;
                if (collecting)
                    setStatus(dialog, "Сбор узлов… " + collected); //$NON-NLS-1$
                else
                    setStatus(dialog, "Поиск…"); //$NON-NLS-1$
                if (running && generation == activeGeneration && !shell.isDisposed())
                    display.timerExec(PROGRESS_INTERVAL_MS, progressTick);
            };
            display.timerExec(PROGRESS_INTERVAL_MS, progressTick);
        }

        private void stopProgressTimer()
        {
            if (progressTick == null)
                return;
            Display display = shell != null && !shell.isDisposed() ? shell.getDisplay() : null;
            if (display != null && !display.isDisposed())
                display.timerExec(-1, progressTick);
            progressTick = null;
        }
    }
}
