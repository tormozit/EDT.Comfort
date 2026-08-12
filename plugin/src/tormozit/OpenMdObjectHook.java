package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.ActiveShellExpression;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;

import java.lang.reflect.Field;

public class OpenMdObjectHook implements IStartup {

    private static final String PATCHED_KEY = "tormozit.mdObjectPatched";
    private static final String COPY_HOOK_KEY = "tormozit.openMdObjectCopyHook"; //$NON-NLS-1$
    private static final String FILTER_GEN_KEY = "tormozit.openMdObject.filterGen"; //$NON-NLS-1$
    private static final String COMFORT_FILTER_JOB_KEY = "tormozit.openMdObject.comfortFilterJob"; //$NON-NLS-1$
    private static final String DEDUPE_HOOK_KEY = "tormozit.openMdObject.dedupeHook"; //$NON-NLS-1$

    private static final String OBJECT_PAIR_CLASS =
            "com._1c.g5.v8.dt.md.ui.dialogs.OpenMdObjectSelectionDialog$ObjectDescriptionPair"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            install(Display.getDefault());
        });
    }

    public static void install(Display display) {
        if (display == null || display.isDisposed()) return;

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell)) return;
                Shell shell = (Shell) event.widget;
                if (shell.isDisposed())
                    return;
                Object dialog = shell.getData();
                String dialogName = dialog != null ? dialog.getClass().getName() : "";
                String shellName = shell.getClass().getName();

                boolean isMdDialog = dialogName.contains("OpenMdObjectSelectionDialog")
                                  || shellName.contains("OpenMdObjectSelectionDialog");
                if (!isMdDialog) return;

                if (shell.getData(PATCHED_KEY) != null) return;

                display.asyncExec(() -> {
                    if (!shell.isDisposed())
                        tryPatchDialog(shell, dialog);
                });
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean tryPatchDialog(Shell shell, Object dialog) {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        try {
            if (dialog == null) return false;

            Object patternControlObj = Global.invoke(dialog, "getPatternControl");
            Text patternText = patternControlObj instanceof Text ? (Text) patternControlObj : null;
            if (patternText == null) {
                OpenMdObjectDebug.log("patch FAIL getPatternControl=" + patternControlObj);
                shell.getDisplay().asyncExec(() -> {
                    if (!shell.isDisposed() && shell.getData(PATCHED_KEY) == null)
                        tryPatchDialog(shell, dialog);
                });
                return false;
            }

            synchronized (shell)
            {
                if (shell.getData(PATCHED_KEY) != null)
                    return true;
                shell.setData(PATCHED_KEY, Boolean.TRUE);
            }

            IBaseLabelProvider labelBase = createFreshMdObjectLabelProvider(dialog);
            if (labelBase == null)
            {
                labelBase = (IBaseLabelProvider) Global.getField(dialog, "mdObjectLabelProvider");
            }
            if (labelBase == null)
            {
                labelBase = (IBaseLabelProvider) Global.invoke(dialog, "getListLabelProvider");
            }
            if (!(labelBase instanceof IBaseLabelProvider))
            {
                OpenMdObjectDebug.log("patch FAIL labelProvider=" + labelBase);
                shell.setData(PATCHED_KEY, null);
                return false;
            }

            ILabelProvider currentLp = (ILabelProvider) labelBase;
            IStyledLabelProvider currentStyled = (labelBase instanceof IStyledLabelProvider)
                    ? (IStyledLabelProvider) labelBase : null;
            ILabelDecorator currentDecorator = (labelBase instanceof ILabelDecorator)
                    ? (ILabelDecorator) labelBase : null;

            OpenMdObjectLabelProvider smartLp = new OpenMdObjectLabelProvider(
                    currentLp, currentStyled, currentDecorator);

            Global.invoke(dialog, "setListLabelProvider", smartLp);
            Global.invoke(dialog, "setListSelectionLabelDecorator", smartLp);

            OpenMdObjectComparator comparator = new OpenMdObjectComparator(dialog, smartLp);
            installLazyListSortHook(dialog, comparator);
            installDedupeHooks(shell, dialog, comparator);

            // --- Убираем штатные слушатели старого поля ---
            for (Listener l : patternText.getListeners(SWT.Modify)) {
                patternText.removeListener(SWT.Modify, l);
            }
            for (Listener l : patternText.getListeners(SWT.KeyDown)) {
                patternText.removeListener(SWT.KeyDown, l);
            }

            final org.eclipse.ui.dialogs.OpenMdObjectItemsFilter[] smartFilterRef =
                    new org.eclipse.ui.dialogs.OpenMdObjectItemsFilter[1];
            final FilterInputBox[] filterInputRef = new FilterInputBox[1];

            smartFilterRef[0] = new org.eclipse.ui.dialogs.OpenMdObjectItemsFilter(
                    (FilteredItemsSelectionDialog) dialog, smartLp, patternText.getText());

            // Создаем FilterInputBox БЕЗ onSearch — фильтрация через ModifyListener
            FilterInputBox filterInput = FilterInputBox.replacePatternText(
                    patternText,
                    FilterInputBox.Scope.OPEN_MD_OBJECT,
                    null);
            if (filterInput == null) {
                OpenMdObjectDebug.log("patch FAIL replacePatternText");
                shell.setData(PATCHED_KEY, null);
                return false;
            }
            filterInputRef[0] = filterInput;

            Control fc = filterInput.inputControl();
            if (fc == null)
                fc = filterInput.widget();
            final Control filterControl = fc;
            updatePatternControlReference(dialog, filterControl);

            // --- Фильтрация через ModifyListener с дебаунсом ---
            final Runnable[] pendingFilterTask = new Runnable[1];

            addFilterModifyListener(filterControl, new ModifyListener() {
                @Override
                public void modifyText(ModifyEvent e) {
                    String pattern = getFilterPattern(filterControl);
                    Display display = filterControl.getDisplay();

                    // Отменяем прошлый таймер
                    if (pendingFilterTask[0] != null) {
                        display.timerExec(-1, pendingFilterTask[0]);
                    }

                    pendingFilterTask[0] = new Runnable() {
                        @Override
                        public void run() {
                            try
                            {
                                applySmartFilter(dialog, smartFilterRef, smartLp, comparator, pattern);
                            }
                            catch (Exception ex)
                            {
                                Global.tempLogException("OpenMdObject", "modifyTimer", ex); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                        }
                    };

                    display.timerExec(150, pendingFilterTask[0]);
                }
            });

            // Отмена таймера при dispose
            filterControl.addDisposeListener(e -> {
                if (pendingFilterTask[0] != null && !filterControl.getDisplay().isDisposed()) {
                    filterControl.getDisplay().timerExec(-1, pendingFilterTask[0]);
                }
            });

            // --- Навигация ---
            Table listTable = getDialogTable(dialog, shell);
            if (listTable != null) {
                FilterInputBoxListNavigation.installTableNavigation(
                        filterControl, listTable, null, false, () ->
                        {
                            Global.invoke(dialog, "okPressed"); //$NON-NLS-1$
                            return true;
                        });
                OpenMdObjectDebug.log("tableNav OK items=" + listTable.getItemCount());
            } else {
                OpenMdObjectDebug.log("tableNav SKIP table not found");
            }

            installCopySupport(shell, dialog);

            try
            {
                applySmartFilter(dialog, smartFilterRef, smartLp, comparator, filterInput.getText(), true);
            }
            catch (Exception ex)
            {
                Global.tempLogException("OpenMdObject", "initialApply", ex); //$NON-NLS-1$ //$NON-NLS-2$
            }

            filterControl.getDisplay().asyncExec(() -> {
                if (filterControl.isDisposed())
                    return;
                if (getFilterPattern(filterControl).isEmpty())
                    refreshHistoryOnUiThread(dialog, smartFilterRef[0], comparator);
            });

            filterInput.scheduleFocusWhenReady();

            OpenMdObjectDebug.log("patch OK initial text=\"" + filterInput.getText() + "\""); //$NON-NLS-1$

            Label target = Global.findLabelByText(shell, "Выберите элемент"); //$NON-NLS-1$
            if (target != null && !target.isDisposed()) {
                target.setText("Фильтр разбивается на слова пробелами и ищется вхождение всех слов одновременно"); //$NON-NLS-1$
                target.getParent().layout();
            }

            return true;

        } catch (Exception e) {
            shell.setData(PATCHED_KEY, null);
            Global.logError("OpenMdObject", "patch", e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    // --- Вспомогательные методы для работы с фильтром ---

    private static String getFilterPattern(Control filterControl) {
        if (filterControl == null || filterControl.isDisposed())
            return ""; //$NON-NLS-1$
        if (filterControl instanceof Text)
            return ((Text) filterControl).getText();
        if (filterControl instanceof StyledText)
            return ((StyledText) filterControl).getText();
        return ""; //$NON-NLS-1$
    }

    private static void addFilterModifyListener(Control filterControl, ModifyListener listener) {
        if (filterControl instanceof Text)
            ((Text) filterControl).addModifyListener(listener);
        else if (filterControl instanceof StyledText)
            ((StyledText) filterControl).addModifyListener(listener);
    }

    private static void updatePatternControlReference(Object dialog, Control control)
    {
        if (dialog == null || control == null)
            return;
        for (String field : new String[] { "patternControl", "fPatternControl" }) //$NON-NLS-1$ //$NON-NLS-2$
            Global.setField(dialog, field, control);
        Global.invoke(dialog, "setPatternControl", control);
    }

    /**
     * Запуск фильтрации без {@code applyFilter}/{@code createFilter}.
     * На каждый schedule — новый {@link org.eclipse.ui.dialogs.OpenMdObjectItemsFilter}:
     * штатный {@code ContentProvider.add} отбрасывает чужие джобы через
     * {@code filter == dialog.filter}; мутация одного экземпляра ломала эту защиту
     * (старый FilterJob продолжал писать после reset → дубли/затроения с задержкой).
     */
    private static void applySmartFilter(Object dialog,
            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter[] smartFilterRef,
            OpenMdObjectLabelProvider smartLp,
            OpenMdObjectComparator comparator,
            String pattern) {
        applySmartFilter(dialog, smartFilterRef, smartLp, comparator, pattern, false);
    }

    private static void applySmartFilter(Object dialog,
            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter[] smartFilterRef,
            OpenMdObjectLabelProvider smartLp,
            OpenMdObjectComparator comparator,
            String pattern,
            boolean forceSchedule) {
        Shell shell = dialogShell(dialog);
        org.eclipse.ui.dialogs.OpenMdObjectItemsFilter smartFilter = smartFilterRef[0];
        Object currentFilter = Global.getField(dialog, "filter");
        boolean skip = smartFilter.shouldSkipSchedule(pattern, currentFilter);
        boolean filterHandoff = currentFilter != smartFilter;

        OpenMdObjectDebug.log("applySmartFilter text=\"" + pattern + "\" smartPatBefore=\"" + smartFilter.getPattern() //$NON-NLS-1$
                + "\" current=" + OpenMdObjectDebug.filterDesc(currentFilter) + " skip=" + skip); //$NON-NLS-1$

        if (skip && !filterHandoff && !forceSchedule) {
            OpenMdObjectDebug.log("applySmartFilter SKIP schedule (pattern unchanged for EDT)");
            if (!pattern.isEmpty())
                refreshTableLabelDecorations(dialog);
            return;
        }

        if (skip && (filterHandoff || forceSchedule)) {
            OpenMdObjectDebug.log("applySmartFilter skip overridden handoff=" + filterHandoff //$NON-NLS-1$
                    + " force=" + forceSchedule); //$NON-NLS-1$
        }

        Job filterHistoryJob = getJobField(dialog, "filterHistoryJob", "fFilterHistoryJob");
        Job filterJob = getJobField(dialog, "filterJob", "fFilterJob");
        OpenMdObjectDebug.log("applySmartFilter cancel jobs fh=" + OpenMdObjectDebug.jobState(filterHistoryJob) //$NON-NLS-1$
                + " fj=" + OpenMdObjectDebug.jobState(filterJob)); //$NON-NLS-1$
        // Сначала инвалидируем in-flight comfortJob (проверка gen перед reset).
        bumpFilterGeneration(shell);
        if (filterHistoryJob != null) filterHistoryJob.cancel();
        if (filterJob != null) filterJob.cancel();
        cancelComfortFilterJob(shell);
        Object cpStop = Global.getField(dialog, "contentProvider"); //$NON-NLS-1$
        if (cpStop != null)
            Global.invoke(cpStop, "stopReloadingCache"); //$NON-NLS-1$

        // Новый экземпляр — как штатный createFilter(): старые FilterJob не пройдут
        // проверку filter == dialog.filter в ContentProvider.add.
        // ItemsFilter.<init> читает dialog.pattern (Text); после replacePatternText
        // тот Text уже disposed → SWTException. На время ctor обнуляем поле.
        org.eclipse.ui.dialogs.OpenMdObjectItemsFilter nextFilter;
        try
        {
            nextFilter = createSmartFilter((FilteredItemsSelectionDialog) dialog, smartLp, pattern);
        }
        catch (Exception e)
        {
            Global.tempLog("OpenMdObject", "createSmartFilter FAIL pat=\"" + pattern + "\": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLogException("OpenMdObject", "createSmartFilter", e); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        smartFilterRef[0] = nextFilter;
        smartLp.setPattern(pattern);
        comparator.setMatcher(new SmartMatcher(pattern));

        Global.setField(dialog, "filter", nextFilter);
        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", nextFilter);

        if (pattern.isEmpty())
        {
            clearSearchCache(dialog);
            refreshHistoryOnUiThread(dialog, nextFilter, comparator);
            Global.tempLog("OpenMdObject", "applySmartFilter history-only"); //$NON-NLS-1$
            return;
        }

        // Не schedule штатный filterHistoryJob: даже после cancel() он может
        // добежать до reset()+filterJob.schedule() → второй fill без reset → дубли.
        scheduleComfortFilterJob(shell, dialog, nextFilter);
    }

    private static Shell dialogShell(Object dialog)
    {
        if (dialog instanceof FilteredItemsSelectionDialog)
            return ((FilteredItemsSelectionDialog) dialog).getShell();
        Object shell = Global.invoke(dialog, "getShell"); //$NON-NLS-1$
        return shell instanceof Shell ? (Shell) shell : null;
    }

    /** Счётчик поколения фильтра на Shell (AtomicLong — чтение из Job без UI-потока). */
    private static AtomicLong filterGeneration(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Object existing = shell.getData(FILTER_GEN_KEY);
        if (existing instanceof AtomicLong)
            return (AtomicLong) existing;
        AtomicLong created = new AtomicLong(0L);
        shell.setData(FILTER_GEN_KEY, created);
        return created;
    }

    private static long bumpFilterGeneration(Shell shell)
    {
        AtomicLong gen = filterGeneration(shell);
        return gen == null ? -1L : gen.incrementAndGet();
    }

    private static boolean isFilterGenerationStale(AtomicLong genCounter, long gen)
    {
        return genCounter == null || gen < 0L || genCounter.get() != gen;
    }

    private static void cancelComfortFilterJob(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        Object prev = shell.getData(COMFORT_FILTER_JOB_KEY);
        if (prev instanceof Job)
            ((Job) prev).cancel();
    }

    /**
     * Свой history→filterJob pipeline с generation-gate вместо штатного
     * {@code FilterHistoryJob} (тот после cancel всё равно зовёт reset+schedule).
     */
    private static void scheduleComfortFilterJob(Shell shell, Object dialog,
            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter filter)
    {
        if (shell == null || shell.isDisposed())
            return;
        AtomicLong genCounter = filterGeneration(shell);
        long gen = bumpFilterGeneration(shell);
        Job prev = (Job) shell.getData(COMFORT_FILTER_JOB_KEY);
        if (prev != null)
            prev.cancel();

        Job comfortJob = new Job("Comfort OpenMdObject filter") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                Object cp = Global.getField(dialog, "contentProvider"); //$NON-NLS-1$
                if (cp == null)
                    return Status.CANCEL_STATUS;
                try
                {
                    // AtomicLong — без shell.getData() из worker (иначе SWT Invalid thread access).
                    if (isFilterGenerationStale(genCounter, gen) || monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    Global.invoke(cp, "reset"); //$NON-NLS-1$
                    if (isFilterGenerationStale(genCounter, gen) || monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    Global.invoke(cp, "addHistoryItems", filter); //$NON-NLS-1$
                    if (isFilterGenerationStale(genCounter, gen) || monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    Global.invoke(cp, "refresh"); //$NON-NLS-1$
                    if (isFilterGenerationStale(genCounter, gen) || monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    Job filterJob = getJobField(dialog, "filterJob", "fFilterJob"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (filterJob != null)
                        filterJob.schedule();
                    Global.tempLog("OpenMdObject", "comfortJob OK gen=" + gen //$NON-NLS-1$
                            + " pat=\"" + filter.getPattern() + "\""); //$NON-NLS-1$ //$NON-NLS-2$
                    return Status.OK_STATUS;
                }
                catch (Exception e)
                {
                    Global.tempLogException("OpenMdObject", "comfortJob", e); //$NON-NLS-1$ //$NON-NLS-2$
                    return Status.CANCEL_STATUS;
                }
            }
        };
        comfortJob.setSystem(true);
        shell.setData(COMFORT_FILTER_JOB_KEY, comfortJob);
        comfortJob.schedule();
    }

    private static void installDedupeHooks(Shell shell, Object dialog, OpenMdObjectComparator comparator)
    {
        if (shell == null || Boolean.TRUE.equals(shell.getData(DEDUPE_HOOK_KEY)))
            return;
        shell.setData(DEDUPE_HOOK_KEY, Boolean.TRUE);
        Job filterJob = getJobField(dialog, "filterJob", "fFilterJob"); //$NON-NLS-1$ //$NON-NLS-2$
        if (filterJob == null)
            return;
        filterJob.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> {
                    if (shell.isDisposed())
                        return;
                    int removed = dedupeContentProviderItems(dialog);
                    if (removed > 0)
                    {
                        resortDialogLists(dialog, comparator);
                        refreshLazyTable(dialog);
                    }
                });
            }
        });
    }


    /**
     * Создаёт фильтр без обращения к disposed {@code Text} в поле {@code pattern}.
     */
    private static org.eclipse.ui.dialogs.OpenMdObjectItemsFilter createSmartFilter(
            FilteredItemsSelectionDialog dialog,
            OpenMdObjectLabelProvider smartLp,
            String pattern)
    {
        Object savedPattern = getFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "pattern"); //$NON-NLS-1$
        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "pattern", null); //$NON-NLS-1$
        try
        {
            return new org.eclipse.ui.dialogs.OpenMdObjectItemsFilter(dialog, smartLp, pattern);
        }
        finally
        {
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "pattern", savedPattern); //$NON-NLS-1$
        }
    }

    private static Object getFieldExactClass(Object obj, Class<?> exactClass, String fieldName)
    {
        try
        {
            Field f = exactClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void refreshHistoryOnUiThread(Object dialog,
            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter smartFilter,
            OpenMdObjectComparator comparator)
    {
        Object contentProvider = Global.getField(dialog, "contentProvider"); //$NON-NLS-1$
        if (contentProvider == null)
            return;
        Global.invoke(contentProvider, "reset"); //$NON-NLS-1$
        Global.invoke(contentProvider, "addHistoryItems", smartFilter); //$NON-NLS-1$
        Global.invoke(contentProvider, "refresh"); //$NON-NLS-1$
        resortDialogLists(dialog, comparator);
        refreshLazyTable(dialog);
    }

    /** Ссылка на выбранный объект для {@link GetRef} и копирования в буфер. */
    public static String getRefFromDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Object dialog = shell.getData();
        if (dialog == null || !dialog.getClass().getName().contains("OpenMdObjectSelectionDialog")) //$NON-NLS-1$
            return null;
        Object viewerObj = Global.getField(dialog, "tableViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TableViewer))
            return null;
        IStructuredSelection sel = ((TableViewer) viewerObj).getStructuredSelection();
        if (sel.isEmpty())
            return null;
        return resolveNavRef(dialog, sel.getFirstElement());
    }

    private static void installLazyListSortHook(Object dialog, OpenMdObjectComparator comparator)
    {
        Object refreshCacheJob = Global.getField(dialog, "refreshCacheJob"); //$NON-NLS-1$
        if (refreshCacheJob == null)
            refreshCacheJob = Global.getField(dialog, "fRefreshCacheJob"); //$NON-NLS-1$
        Object refreshJob = refreshCacheJob != null
                ? Global.getField(refreshCacheJob, "refreshJob") : null; //$NON-NLS-1$
        if (!(refreshJob instanceof Job))
            return;
        ((Job) refreshJob).addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void aboutToRun(IJobChangeEvent event)
            {
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.syncExec(() -> {
                    dedupeContentProviderItems(dialog);
                    resortDialogLists(dialog, comparator);
                });
            }
        });
    }

    /**
     * Схлопывает дубли ObjectDescriptionPair с одним EObjectURI (identity Set их не ловит).
     * При коллизии оставляем экземпляр из SelectionHistory — иначе
     * {@code isHistoryElement} (по identity) ломается и пропадает секция «последние».
     */
    @SuppressWarnings("unchecked")
    private static int dedupeContentProviderItems(Object dialog)
    {
        Object cp = Global.getField(dialog, "contentProvider"); //$NON-NLS-1$
        if (cp == null)
            return 0;
        Object itemsObj = Global.getField(cp, "items"); //$NON-NLS-1$
        if (!(itemsObj instanceof Set))
            return 0;
        Set<Object> items = (Set<Object>) itemsObj;
        List<Object> snapshot;
        synchronized (items)
        {
            snapshot = new ArrayList<>(items);
        }
        Map<String, Object> unique = new LinkedHashMap<>();
        int anon = 0;
        for (Object item : snapshot)
        {
            String key = itemDedupeKey(item);
            if (key == null)
                key = "anon-" + (++anon) + "-" + System.identityHashCode(item); //$NON-NLS-1$ //$NON-NLS-2$
            Object prev = unique.get(key);
            if (prev == null)
                unique.put(key, item);
            else if (!isSelectionHistoryElement(dialog, prev) && isSelectionHistoryElement(dialog, item))
                unique.put(key, item);
        }
        int removed = snapshot.size() - unique.size();
        if (removed <= 0)
            return 0;
        synchronized (items)
        {
            items.clear();
            items.addAll(unique.values());
        }
        dedupeProviderListByKey(dialog, cp, "lastFilteredItems"); //$NON-NLS-1$
        dedupeProviderListByKey(dialog, cp, "lastSortedItems"); //$NON-NLS-1$
        Global.tempLog("OpenMdObject", "dedupe removed=" + removed + " left=" + unique.size()); //$NON-NLS-1$ //$NON-NLS-2$
        return removed;
    }

    @SuppressWarnings("unchecked")
    private static void dedupeProviderListByKey(Object dialog, Object cp, String fieldName)
    {
        Object listObj = Global.getField(cp, fieldName);
        if (!(listObj instanceof List))
            return;
        List<Object> list = (List<Object>) listObj;
        synchronized (list)
        {
            Map<String, Object> unique = new LinkedHashMap<>();
            int anon = 0;
            for (Object item : list)
            {
                if (isItemsListSeparator(item))
                {
                    unique.put("sep-" + (++anon), item); //$NON-NLS-1$
                    continue;
                }
                String key = itemDedupeKey(item);
                if (key == null)
                    key = "anon-" + (++anon) + "-" + System.identityHashCode(item); //$NON-NLS-1$ //$NON-NLS-2$
                Object prev = unique.get(key);
                if (prev == null)
                    unique.put(key, item);
                else if (!isSelectionHistoryElement(dialog, prev) && isSelectionHistoryElement(dialog, item))
                    unique.put(key, item);
            }
            if (unique.size() == list.size())
                return;
            list.clear();
            list.addAll(unique.values());
        }
    }

    private static boolean isItemsListSeparator(Object item)
    {
        if (item == null)
            return false;
        String name = item.getClass().getName();
        return name.contains("ItemsListSeparator"); //$NON-NLS-1$
    }

    private static boolean isSelectionHistoryElement(Object dialog, Object item)
    {
        if (dialog == null || item == null || isItemsListSeparator(item))
            return false;
        Object history = Global.invoke(dialog, "getSelectionHistory"); //$NON-NLS-1$
        if (history == null)
        {
            Object cp = Global.getField(dialog, "contentProvider"); //$NON-NLS-1$
            if (cp != null)
                history = Global.invoke(cp, "getSelectionHistory"); //$NON-NLS-1$
        }
        if (history == null)
            return false;
        return Boolean.TRUE.equals(Global.invoke(history, "contains", item)); //$NON-NLS-1$
    }

    private static String itemDedupeKey(Object item)
    {
        if (item == null)
            return null;
        try
        {
            Object desc = Global.getField(item, "description"); //$NON-NLS-1$
            if (desc == null)
                desc = Global.getField(item, "descriptionRu"); //$NON-NLS-1$
            if (desc == null)
                return null;
            Object uri = Global.invoke(desc, "getEObjectURI"); //$NON-NLS-1$
            return uri != null ? uri.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void resortDialogLists(Object dialog, OpenMdObjectComparator comparator)
    {
        if (dialog == null || comparator == null)
            return;
        Object cp = Global.getField(dialog, "contentProvider"); //$NON-NLS-1$
        if (cp == null)
            return;
        comparator.refreshHistoryOrder(dialog, cp);
        // Пустой фильтр: только история — новейшие первыми (штатный HistoryComparator
        // внутри getSortedItems сортирует историю алфавитом через getItemsComparator).
        if (comparator.matcher == null || comparator.matcher.isEmpty)
        {
            sortProviderListsByHistory(dialog, cp);
            return;
        }
        // История сверху (как HistoryComparator), иначе getFilteredItems не вставит
        // разделитель «Совпадения в рабочей области».
        sortProviderList(cp, "lastSortedItems", comparator); //$NON-NLS-1$
        sortProviderList(cp, "lastFilteredItems", comparator); //$NON-NLS-1$
    }

    /**
     * Заполняет {@code lastSortedItems} из {@code items} в порядке доступа истории
     * (новые сверху), чтобы штатный {@code getSortedItems} не пересортировал алфавитом
     * (пропуск, когда {@code lastSortedItems.size() == items.size()}).
     */
    @SuppressWarnings("unchecked")
    private static void sortProviderListsByHistory(Object dialog, Object cp)
    {
        Map<String, Integer> orderByUri = buildHistoryAccessOrder(dialog, cp);
        Object itemsObj = Global.getField(cp, "items"); //$NON-NLS-1$
        Set<Object> items = itemsObj instanceof Set ? (Set<Object>) itemsObj : null;
        fillAndSortByHistory(cp, "lastSortedItems", items, orderByUri); //$NON-NLS-1$
        fillAndSortByHistory(cp, "lastFilteredItems", items, orderByUri); //$NON-NLS-1$
    }

    /** URI → индекс в SelectionHistory (больше = новее; LinkedHashSet: старые→новые). */
    private static Map<String, Integer> buildHistoryAccessOrder(Object dialog, Object cp)
    {
        Map<String, Integer> order = new HashMap<>();
        Object history = Global.invoke(cp, "getSelectionHistory"); //$NON-NLS-1$
        if (history == null)
            history = Global.invoke(dialog, "getSelectionHistory"); //$NON-NLS-1$
        if (history == null)
            return order;
        Object raw = Global.invoke(history, "getHistoryItems"); //$NON-NLS-1$
        if (!(raw instanceof Object[]))
            return order;
        Object[] historyItems = (Object[]) raw;
        for (int i = 0; i < historyItems.length; i++)
        {
            String key = itemDedupeKey(historyItems[i]);
            if (key != null)
                order.put(key, i);
        }
        return order;
    }

    @SuppressWarnings("unchecked")
    private static void fillAndSortByHistory(Object cp, String fieldName, Set<Object> items,
            Map<String, Integer> orderByUri)
    {
        Object listObj = Global.getField(cp, fieldName);
        if (!(listObj instanceof List))
            return;
        List<Object> list = (List<Object>) listObj;
        synchronized (list)
        {
            // Всегда пересобираем из items: после reset lastFilteredItems не чистится,
            // а совпадение size со старым содержимым оставило бы чужой порядок/состав.
            if (items != null)
            {
                synchronized (items)
                {
                    list.clear();
                    list.addAll(items);
                }
            }
            list.sort((a, b) -> compareByHistoryAccess(a, b, orderByUri));
        }
    }

    /** Новее (больший индекс истории) выше; вне истории — в конец, алфавит между собой. */
    private static int compareByHistoryAccess(Object a, Object b, Map<String, Integer> orderByUri)
    {
        Integer ia = orderIndex(a, orderByUri);
        Integer ib = orderIndex(b, orderByUri);
        if (ia != null && ib != null)
            return Integer.compare(ib, ia);
        if (ia != null)
            return -1;
        if (ib != null)
            return 1;
        String ta = a != null ? a.toString() : ""; //$NON-NLS-1$
        String tb = b != null ? b.toString() : ""; //$NON-NLS-1$
        return ta.compareToIgnoreCase(tb);
    }

    private static Integer orderIndex(Object item, Map<String, Integer> orderByUri)
    {
        if (item == null || orderByUri.isEmpty())
            return null;
        String key = itemDedupeKey(item);
        return key != null ? orderByUri.get(key) : null;
    }

    @SuppressWarnings("unchecked")
    private static void sortProviderList(Object cp, String fieldName, OpenMdObjectComparator comparator)
    {
        Object listObj = Global.getField(cp, fieldName);
        if (!(listObj instanceof List))
            return;
        List<Object> list = (List<Object>) listObj;
        synchronized (list)
        {
            list.sort(comparator::compareItems);
        }
    }

    /** ILazyContentProvider: обновить виртуальную таблицу после сортировки списка. */
    private static void refreshLazyTable(Object dialog)
    {
        TableViewer viewer = getTableViewer(dialog);
        if (viewer == null || viewer.getControl().isDisposed())
            return;
        Object cp = Global.getField(dialog, "contentProvider"); //$NON-NLS-1$
        if (cp != null)
        {
            Object count = Global.invoke(cp, "getNumberOfElements"); //$NON-NLS-1$
            if (count instanceof Integer)
                viewer.setItemCount((Integer) count);
        }
        viewer.refresh();
    }

    private static TableViewer getTableViewer(Object dialog)
    {
        Object viewerObj = Global.getField(dialog, "tableViewer"); //$NON-NLS-1$
        if (viewerObj instanceof TableViewer)
            return (TableViewer) viewerObj;
        for (String field : new String[] { "fTableViewer" }) //$NON-NLS-1$
        {
            viewerObj = Global.getField(dialog, field);
            if (viewerObj instanceof TableViewer)
                return (TableViewer) viewerObj;
        }
        return null;
    }

    /** Только подсветка совпадений, порядок строк не меняется. */
    private static void refreshTableLabelDecorations(Object dialog)
    {
        TableViewer viewer = getTableViewer(dialog);
        if (viewer == null || viewer.getControl().isDisposed())
            return;
        viewer.refresh(true);
    }

    private static void installCopySupport(Shell shell, Object dialog)
    {
        if (Boolean.TRUE.equals(shell.getData(COPY_HOOK_KEY)))
            return;
        IHandlerService handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
        if (handlerService == null)
            return;
        AbstractHandler handler = new AbstractHandler()
        {
            @Override
            public Object execute(ExecutionEvent event)
            {
                copySelectedToClipboard(dialog, shell);
                return null;
            }
        };
        IHandlerActivation activation = handlerService.activateHandler(
                "org.eclipse.ui.edit.copy", handler, new ActiveShellExpression(shell)); //$NON-NLS-1$
        shell.setData(COPY_HOOK_KEY, Boolean.TRUE);
        shell.addDisposeListener(e -> handlerService.deactivateHandler(activation));
    }

    private static void copySelectedToClipboard(Object dialog, Shell shell)
    {
        Object viewerObj = Global.getField(dialog, "tableViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TableViewer))
            return;
        IStructuredSelection sel = ((TableViewer) viewerObj).getStructuredSelection();
        if (sel.isEmpty())
            return;
        String ref = resolveNavRef(dialog, sel.getFirstElement());
        if (ref == null || ref.isEmpty())
            return;
        Clipboard clipboard = new Clipboard(shell.getDisplay());
        try
        {
            clipboard.setContents(
                    new Object[] { ref },
                    new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
        ToastNotification.show("Скопировано", ref, 4000); //$NON-NLS-1$
        OpenMdObjectDebug.log("copy ref=\"" + ref + "\""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String resolveNavRef(Object dialog, Object element)
    {
        if (dialog == null || element == null)
            return null;
        if (!OBJECT_PAIR_CLASS.equals(element.getClass().getName()))
            return null;
        Object engine = Global.getField(dialog, "mdObjectsEngine"); //$NON-NLS-1$
        Object description = Global.getField(element, "description"); //$NON-NLS-1$
        if (engine == null || description == null)
            return null;
        Object fullName = Global.invoke(engine, "getFullName", description); //$NON-NLS-1$
        if (!(fullName instanceof String))
            return null;
        return navRefFromDisplayFullName((String) fullName);
    }

    static String navRefFromDisplayFullName(String fullName)
    {
        if (fullName == null || fullName.isEmpty())
            return null;
        int linkSep = fullName.indexOf(" / "); //$NON-NLS-1$
        if (linkSep >= 0)
            return fullName.substring(linkSep + 3).trim();
        return fullName.trim();
    }

    private static void clearSearchCache(Object dialog)
    {
        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "lastCompletedFilter", null); //$NON-NLS-1$
        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "lastCompletedResult", null); //$NON-NLS-1$
    }

    private static IBaseLabelProvider createFreshMdObjectLabelProvider(Object dialog)
    {
        try
        {
            Class<?> dialogClass = dialog.getClass();
            Class<?> lpClass = Class.forName(
                    "com._1c.g5.v8.dt.md.ui.dialogs.OpenMdObjectSelectionDialog$MdObjectLabelProvider"); //$NON-NLS-1$
            java.lang.reflect.Constructor<?> ctor = lpClass.getDeclaredConstructor(dialogClass);
            ctor.setAccessible(true);
            return (IBaseLabelProvider) ctor.newInstance(dialog);
        }
        catch (Exception e)
        {
            OpenMdObjectDebug.log("createFreshMdLabelProvider FAIL: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static Table getDialogTable(Object dialog, Shell shell)
    {
        TableViewer viewer = getTableViewer(dialog);
        if (viewer != null && !viewer.getControl().isDisposed())
            return viewer.getTable();
        if (shell != null && !shell.isDisposed())
        {
            Table fromShell = Global.findControl((Composite) shell, Table.class, t -> true);
            if (fromShell != null)
                return fromShell;
        }
        return null;
    }

    private static Job getJobField(Object dialog, String... fieldNames) {
        for (String name : fieldNames) {
            Object job = Global.getField(dialog, name);
            if (job instanceof Job)
                return (Job) job;
        }
        return null;
    }

    private static void setFieldExactClass(Object obj, Class<?> exactClass, String fieldName, Object value) {
        try {
            Field f = exactClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            OpenMdObjectDebug.log("setField FAIL " + exactClass.getName() + "." + fieldName + ": " + e.getMessage());
        }
    }

    private static final class OpenMdObjectComparator {

        private final Object dialog;
        private final ILabelProvider labelProvider;
        private SmartMatcher matcher;
        private Map<String, Integer> historyOrder = Map.of();
        private final Map<Object, Integer> premiumCache = new HashMap<>();

        public OpenMdObjectComparator(Object dialog, ILabelProvider labelProvider) {
            this.dialog = dialog;
            this.labelProvider = labelProvider;
        }

        public void setMatcher(SmartMatcher matcher) {
            this.matcher = matcher;
            this.premiumCache.clear();
        }

        void refreshHistoryOrder(Object dialog, Object cp) {
            this.historyOrder = buildHistoryAccessOrder(dialog != null ? dialog : this.dialog, cp);
        }

        public int compareItems(Object o1, Object o2) {
            return compareElements(o1, o2);
        }

        private int compareElements(Object o1, Object o2) {
            boolean sep1 = isItemsListSeparator(o1);
            boolean sep2 = isItemsListSeparator(o2);
            if (sep1 || sep2) {
                if (sep1 && sep2)
                    return 0;
                // Разделитель — после истории, перед остальными (как штатный getFilteredItems).
                if (sep1)
                    return isSelectionHistoryElement(dialog, o2) ? 1 : -1;
                return isSelectionHistoryElement(dialog, o1) ? -1 : 1;
            }

            boolean h1 = isSelectionHistoryElement(dialog, o1);
            boolean h2 = isSelectionHistoryElement(dialog, o2);
            if (h1 != h2)
                return h1 ? -1 : 1;

            if (h1)
                return compareByHistoryAccess(o1, o2, historyOrder);

            if (matcher == null || matcher.isEmpty)
                return compareAlphabetically(o1, o2);

            int p1 = premiumCache.computeIfAbsent(o1,
                    k -> matcher.computeNamePremium(getObjectName(labelProvider.getText(k))));
            int p2 = premiumCache.computeIfAbsent(o2,
                    k -> matcher.computeNamePremium(getObjectName(labelProvider.getText(k))));
            if (p1 != p2)
                return Integer.compare(p2, p1);
            return compareAlphabetically(o1, o2);
        }

        private String getObjectName(String fullText) {
            return org.eclipse.ui.dialogs.OpenMdObjectItemsFilter.getObjectName(fullText);
        }

        private int compareAlphabetically(Object o1, Object o2) {
            String t1 = labelProvider.getText(o1);
            String t2 = labelProvider.getText(o2);
            if (t1 == null) return t2 == null ? 0 : 1;
            if (t2 == null) return -1;
            return t1.compareToIgnoreCase(t2);
        }
    }


    /**
     * Логи диалога «Открыть объект метаданных» через {@link Global}.
     * Включение: Параметры → Комфорт → «Общее логирование».
     */
    private static final class OpenMdObjectDebug
    {
        private static final String TAG = "OpenMdObject"; //$NON-NLS-1$

        private OpenMdObjectDebug() {}

        public static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        public static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }

        public static String filterDesc(Object filter)
        {
            if (filter == null)
                return "null";
            String type = filter.getClass().getSimpleName();
            if (filter instanceof org.eclipse.ui.dialogs.OpenMdObjectItemsFilter) {
                org.eclipse.ui.dialogs.OpenMdObjectItemsFilter f =
                        (org.eclipse.ui.dialogs.OpenMdObjectItemsFilter) filter;
                return type + "@" + Integer.toHexString(System.identityHashCode(filter))
                        + " pat=\"" + f.getPattern() + "\""; //$NON-NLS-1$
            }
            try {
                Object pat = Global.invoke(filter, "getPattern");
                return type + " pat=\"" + pat + "\""; //$NON-NLS-1$
            } catch (Exception e) {
                return type;
            }
        }

        public static String jobState(Job job)
        {
            if (job == null)
                return "null";
            switch (job.getState()) {
                case Job.RUNNING: return "RUNNING";
                case Job.WAITING: return "WAITING";
                case Job.SLEEPING: return "SLEEPING";
                default: return "NONE";
            }
        }
    }


    private static class OpenMdObjectLabelProvider extends LabelProvider implements IStyledLabelProvider, ILabelDecorator {

        private final ILabelProvider baseProvider;
        private final IStyledLabelProvider baseStyled;
        private final ILabelDecorator baseDecorator;
        private SmartMatcher matcher;

        public OpenMdObjectLabelProvider(ILabelProvider baseProvider,
                                          IStyledLabelProvider baseStyled,
                                          ILabelDecorator baseDecorator) {
            this.baseProvider = baseProvider;
            this.baseStyled = baseStyled;
            this.baseDecorator = baseDecorator;
            this.matcher = new SmartMatcher("");
        }

        public void setPattern(String pattern) {
            this.matcher = new SmartMatcher(pattern);
        }

        @Override
        public String getText(Object element) {
            return baseProvider != null ? baseProvider.getText(element) : super.getText(element);
        }

        @Override
        public Image getImage(Object element) {
            return baseProvider != null ? baseProvider.getImage(element) : super.getImage(element);
        }

        @Override
        public StyledString getStyledText(Object element) {
            StyledString styledString;
            if (baseStyled != null) {
                styledString = baseStyled.getStyledText(element);
                if (styledString == null) styledString = new StyledString(getText(element));
            } else {
                styledString = new StyledString(getText(element));
            }

            // Красим только чистое имя (до " - "): FilteredItemsSelectionDialog для ВЫДЕЛЕННОЙ
            // строки сам прогоняет наш styledString через родной selectionDecorator.decorateText
            // и StyledCellLabelProvider.styleDecoratedString(decorated, null, string) — если бы мы
            // сами дописывали " / Родитель.Имя" в styledString, родной decorator получал бы уже
            // изменённый нами текст, не находил бы совпадения и отбрасывал ВСЕ стили (включая
            // подсветку имени), а сам он красит добавленный им суффикс без цвета (см. "null" —
            // "no need to add colors when element is selected" в его исходнике). Поэтому полное
            // имя с родительской секцией показывает и красит только родной decorateText (ниже),
            // а мы подсвечиваем исключительно имя объекта — секционным фильтром по последней
            // секции, обычным — по всем фрагментам.
            String plainText = styledString.getString();
            String objectName = org.eclipse.ui.dialogs.OpenMdObjectItemsFilter.getObjectName(plainText);
            int nameOffset = plainText.indexOf(objectName);
            if (nameOffset < 0) nameOffset = 0;
            java.util.List<SmartMatcher.HighlightRange> ranges = matcher.hasMultipleSections()
                ? matcher.getLastSectionHighlightRanges(objectName)
                : matcher.getHighlightRanges(objectName);
            for (SmartMatcher.HighlightRange range : ranges) {
                styledString.setStyle(nameOffset + range.offset, range.length, SmartMatchHighlight.styler());
            }
            return styledString;
        }

        @Override
        public Image decorateImage(Image image, Object element) {
            return baseDecorator != null ? baseDecorator.decorateImage(image, element) : image;
        }

        @Override
        public String decorateText(String text, Object element) {
            return baseDecorator != null ? baseDecorator.decorateText(text, element) : text;
        }

        @Override
        public void dispose() {
            super.dispose();
        }
    }

}