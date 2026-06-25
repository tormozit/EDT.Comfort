package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlExtension5;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.xtext.ui.editor.hover.html.IXtextBrowserInformationControl;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;

/**
 * Вспомогательные операции с {@code CompletionProposalPopup}.
 *
 * <p>При вводе символов — prepend к {@code fFilterRunnable} планирует debounced
 * {@link #recomputePopupList} (smart-фильтр) вместо {@code validate} по полному списку.
 *
 * <p>{@link #recomputePopupList} — только для явных действий (toggle, Ctrl+Space, догрузка кэша).
 */
public final class ContentAssistPopupSync
{
    private static final IdentityHashMap<Object, Runnable> ORIGINAL_FILTER_RUNNABLES =
        new IdentityHashMap<>();
    /** Popup, для которого сохранён оригинальный runnable (на случай hide до session end). */
    private static final IdentityHashMap<ContentAssistant, Object> HIJACKED_POPUPS =
        new IdentityHashMap<>();
    private static final ThreadLocal<SavedSelection> pendingFilterToggleSelection =
        new ThreadLocal<>();
    private static final IdentityHashMap<Object, PendingDebouncedFilter> PENDING_FILTER_TASKS =
        new IdentityHashMap<>();

    private static final int FILTER_DEBOUNCE_MS = 60;
    private static final int INITIAL_POPUP_DISPLAY_LIMIT = 150;
    private static final int POPUP_DISPLAY_STEP = 100;
    private static final int POPUP_LOAD_MORE_MARGIN = 20;
    private static final AtomicInteger FILTER_TASK_GEN = new AtomicInteger();
    private static final AtomicInteger CARET_TASK_GEN = new AtomicInteger();
    private static String lastSyncedFilter = ""; //$NON-NLS-1$
    private static int lastSyncedCount = -1;
    private static int lastSyncedDisplayCount = -1;
    private static int lastFullFilteredCount = 0;
    private static int popupDisplayLimit = INITIAL_POPUP_DISPLAY_LIMIT;
    private static boolean lastSyncedSmartEnabled = true;
    private static final ThreadLocal<Boolean> RECOMPUTE_GUARD =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final IdentityHashMap<Object, Listener> POPUP_SCROLL_LISTENERS =
        new IdentityHashMap<>();
    private static final IdentityHashMap<SourceViewer, PendingDebouncedFilter> PENDING_CARET_TASKS =
        new IdentityHashMap<>();
    private static final int[] SESSION_POPUP_SYNC_DELAYS_MS = { 0, 30, 80, 150, 300 };
    private static final AtomicInteger SESSION_SYNC_GEN = new AtomicInteger();
    private static final IdentityHashMap<SourceViewer, Runnable> PENDING_SESSION_SYNC =
        new IdentityHashMap<>();

    private static final class PendingDebouncedFilter
    {
        final Runnable task;
        final Display display;

        PendingDebouncedFilter(Runnable task, Display display)
        {
            this.task = task;
            this.display = display;
        }
    }

    private static Field popupField;
    private static Field fComputedProposalsField;
    private static Field fFilteredProposalsField;
    private static Field fIsFilteredSubsetField;
    private static Field fIsFilterPendingField;
    private static Field fFilterRunnableField;
    private static Field fFilterOffsetField;
    private static Field fLastCompletionOffsetField;
    private static Field fInvocationOffsetField;
    private static Field fDocumentEventsField;
    private static Method hidePopupMethod;
    private static Field fProposalTableField;
    private static Field fProposalShellField;
    private static Field fMessageTextField;
    private static Method setStatusLineVisibleMethod;
    private static Field fAdditionalInfoControllerField;
    private static Method setProposalsMethod;
    private static Method selectProposalMethod;
    private static Method configureAndMakeVisibleMethod;
    private static Method handleTableSelectionChangedMethod;
    private static Field fAdditionalInfoProposalField;
    private static Field fAdditionalInfoInformationField;

    private ContentAssistPopupSync() {}

    // ---- filterTracker prepend ------------------------------------------------

    /**
     * Подменяет {@code fFilterRunnable}: debounce + {@link #recomputePopupList}
     * (или штатный validate при выключенном smart-фильтре).
     */
    public static void installFilterTrackerPrepend(ContentAssistant assistant, SourceViewer viewer)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            initPopupReflection(popup);
            if (ORIGINAL_FILTER_RUNNABLES.containsKey(popup))
                return;

            Runnable original = (Runnable) fFilterRunnableField.get(popup);
            ORIGINAL_FILTER_RUNNABLES.put(popup, original);
            HIJACKED_POPUPS.put(assistant, popup);

            Runnable replacement = () -> {
                if (isRecomputeInProgress())
                    return;
                scheduleDebouncedNativeFilter(popup, viewer, original);
            };
            fFilterRunnableField.set(popup, replacement);
            installPopupScrollWatcher(popup, assistant, viewer,
                ContentAssistSessionReloader.getActiveProcessor());
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("filterRunnable prepend ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static void uninstallFilterTrackerPrepend(ContentAssistant assistant)
    {
        if (assistant == null)
            return;
        try
        {
            Object popup = null;
            try
            {
                popup = getPopup(assistant);
            }
            catch (Exception ignored) {}
            if (popup == null)
                popup = HIJACKED_POPUPS.get(assistant);
            if (popup == null)
                return;
            cancelDebouncedNativeFilter(popup);
            uninstallPopupScrollWatcher(popup);
            initPopupReflection(popup);
            Runnable original = ORIGINAL_FILTER_RUNNABLES.remove(popup);
            if (original != null)
                fFilterRunnableField.set(popup, original);
            HIJACKED_POPUPS.remove(assistant);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("filterRunnable restore ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    // ---- session start sync (popup may appear after assistSessionStarted) ----

    /**
     * Синхронизация offset'ов popup, prepend filterRunnable и пересчёт списка.
     * Повторяется с задержкой: popup часто ещё null в {@code assistSessionStarted}.
     */
    public static void scheduleSessionPopupSync(ContentAssistant assistant, SourceViewer viewer,
                                                SmartContentAssistProcessor processor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (assistant == null || viewer == null || processor == null)
            return;
        cancelSessionPopupSync(viewer);
        Control widget = viewer.getTextWidget() != null ? (Control) viewer.getTextWidget() : null;
        if (widget == null || widget.isDisposed())
            return;
        Display display = widget.getDisplay();
        if (display == null || display.isDisposed())
            return;

        final int gen = SESSION_SYNC_GEN.get();
        final int[] step = { 0 };
        Runnable task = new Runnable() {
            @Override
            public void run()
            {
                PENDING_SESSION_SYNC.remove(viewer);
                if (widget.isDisposed() || gen != SESSION_SYNC_GEN.get())
                    return;
                if (ContentAssistSessionReloader.getActiveAssistant() != assistant)
                    return;
                try
                {
                    int caret = SmartContentAssistProcessor.resolveSessionCaret(assistant, viewer);
                    ContentAssistPopupUi.ensureFilterToggle(assistant, viewer, processor);
                    installFilterTrackerPrepend(assistant, viewer);
                    if (isPopupVisible(assistant))
                    {
                        IDocument doc = viewer.getDocument();
                        if (caret >= 0)
                            SmartContentAssistProcessor.primeFilterTrackerOnly(viewer, caret);
                        if (shouldRecomputePopupList(SmartFilterTracker.getCurrentFilter(),
                            false, doc, caret))
                        {
                            runStockFilterRunnable(assistant);
                            if (recomputePopupList(assistant, viewer, processor, caret))
                            {
                                refreshAdditionalInfo(assistant);
                                return;
                            }
                        }
                        else
                            return;
                    }
                }
                catch (Exception e)
                {
                    ContentAssistDebug.log("sessionPopupSync ERROR: " + e.getMessage()); //$NON-NLS-1$
                }
                step[0]++;
                if (step[0] < SESSION_POPUP_SYNC_DELAYS_MS.length)
                {
                    PENDING_SESSION_SYNC.put(viewer, this);
                    display.timerExec(SESSION_POPUP_SYNC_DELAYS_MS[step[0]], this);
                }
            }
        };
        PENDING_SESSION_SYNC.put(viewer, task);
        display.timerExec(SESSION_POPUP_SYNC_DELAYS_MS[0], task);
    }

    public static void cancelSessionPopupSync(SourceViewer viewer)
    {
        SESSION_SYNC_GEN.incrementAndGet();
        if (viewer == null)
            return;
        Runnable pending = PENDING_SESSION_SYNC.remove(viewer);
        if (pending == null)
            return;
        if (viewer.getTextWidget() instanceof Control)
        {
            Control widget = (Control) viewer.getTextWidget();
            if (!widget.isDisposed())
                widget.getDisplay().timerExec(-1, pending);
        }
    }

    // ---- recomputePopupList (explicit user action) ----------------------------

    /**
     * Пересчёт отфильтрованного списка и обновление таблицы popup
     * (debounced ввод, toggle, Ctrl+Space, догрузка кэша).
     */
    static void clearSyncState()
    {
        lastSyncedFilter = ""; //$NON-NLS-1$
        lastSyncedCount = -1;
        lastSyncedDisplayCount = -1;
        lastFullFilteredCount = 0;
        popupDisplayLimit = INITIAL_POPUP_DISPLAY_LIMIT;
        lastSyncedSmartEnabled = !SmartAssistFilterState.isSmartFilterEnabled();
        FILTER_TASK_GEN.incrementAndGet();
    }

    public static boolean isRecomputeInProgress()
    {
        return Boolean.TRUE.equals(RECOMPUTE_GUARD.get());
    }

    public static boolean recomputePopupList(ContentAssistant assistant, SourceViewer viewer,
                                             SmartContentAssistProcessor processor)
    {
        return recomputePopupList(assistant, viewer, processor, -1);
    }

    /**
     * @param caretOverride позиция каретки; {@code >= 0} — явная (старт сессии),
     *                      {@code < 0} — {@link SmartContentAssistProcessor#resolveWidgetCaret}
     */
    public static boolean recomputePopupList(ContentAssistant assistant, SourceViewer viewer,
                                             SmartContentAssistProcessor processor,
                                             int caretOverride)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        if (Boolean.TRUE.equals(RECOMPUTE_GUARD.get()))
            return false;
        RECOMPUTE_GUARD.set(Boolean.TRUE);
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
            {
                ContentAssistDebug.log("popupSync SKIP: popup null"); //$NON-NLS-1$
                return false;
            }
            initPopupReflection(popup);
            if (shouldCloseOnPendingDocumentEvents(popup))
            {
                clearPendingDocumentEvents(popup);
                hideProposalPopup(popup);
                return false;
            }
            hideStatusLine(assistant, popup);

            SavedSelection pending = takePendingFilterToggleSelection();
            final boolean restoreAfterFilterToggle = pending != null;
            final SavedSelection saved = pending != null ? pending : saveSelection(popup);

            int caret = caretOverride >= 0 ? caretOverride
                : SmartContentAssistProcessor.resolveWidgetCaret(viewer);
            if (caret >= 0)
                SmartContentAssistProcessor.primeFilterTrackerOnly(viewer, caret);

            boolean inLiteralRecompute = SmartContentAssistProcessor.isStringLiteralAssistContext(
                viewer, caret);

            if (inLiteralRecompute)
            {
                runStockFilterRunnable(assistant);
                finishSyncCycle(popup);
                // #region agent log
                ContentAssistDebug.agentLog("H2", "recomputePopupList", "literalStockOnly", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"runId\":\"post-fix\",\"tableRows\":" + tableItemCount(popup) + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // #endregion
                return true;
            }

            final boolean resetToFirst = restoreAfterFilterToggle
                ? SmartAssistFilterState.isSmartFilterEnabled()
                : shouldResetSelectionToFirst();
            String filter = SmartFilterTracker.getCurrentFilter();
            ICompletionProposal[] proposals = processor.filterCachedProposalsForPopup(viewer, caret,
                filter);
            if (proposals == null)
                proposals = processor.computeForPopupRefresh(viewer, caret);
            if (proposals == null)
                proposals = new ICompletionProposal[0];
            final int fullProposalCount = proposals.length;

            filter = SmartFilterTracker.getCurrentFilter();
            boolean smartEnabled = SmartAssistFilterState.isSmartFilterEnabled();
            if (!filter.equals(lastSyncedFilter))
                popupDisplayLimit = INITIAL_POPUP_DISPLAY_LIMIT;
            int displayCount = Math.min(proposals.length, popupDisplayLimit);
            if (filter.equals(lastSyncedFilter)
                && proposals.length == lastSyncedCount
                && displayCount == lastSyncedDisplayCount
                && smartEnabled == lastSyncedSmartEnabled)
            {
                finishSyncCycle(popup);
                return true;
            }
            if (proposals.length == 0 && filter.equals(lastSyncedFilter) && lastSyncedCount > 0
                && !smartEnabled)
            {
                finishSyncCycle(popup);
                return true;
            }

            lastSyncedFilter = filter;
            lastSyncedCount = proposals.length;
            lastSyncedDisplayCount = displayCount;
            lastSyncedSmartEnabled = smartEnabled;
            lastFullFilteredCount = proposals.length;

            ArrayList<ICompletionProposal> list = new ArrayList<>(Arrays.asList(proposals));
            final boolean stockToggleOff = restoreAfterFilterToggle && !smartEnabled;
            if (restoreAfterFilterToggle)
            {
                int targetIdx = findProposalIndex(list, saved);
                if (targetIdx >= 0 && targetIdx >= popupDisplayLimit)
                    popupDisplayLimit = Math.min(list.size(),
                        targetIdx + POPUP_LOAD_MORE_MARGIN + 1);
            }
            else if (!smartEnabled && !filter.isEmpty())
            {
                int prefixIdx = findFirstPrefixMatchIndex(list, filter);
                if (prefixIdx >= 0 && prefixIdx >= popupDisplayLimit)
                    popupDisplayLimit = Math.min(list.size(),
                        prefixIdx + POPUP_LOAD_MORE_MARGIN + 1);
            }
            final ArrayList<ICompletionProposal> displayList;
            if (stockToggleOff)
                displayList = list;
            else if (list.size() > popupDisplayLimit)
                displayList = new ArrayList<>(list.subList(0, popupDisplayLimit));
            else
                displayList = list;

            if (fIsFilteredSubsetField != null)
                fIsFilteredSubsetField.setBoolean(popup, false);

            if (stockToggleOff)
            {
                runWithPopupRedrawSuppressed(popup, () -> {
                    try
                    {
                        applyPopupListSync(popup, assistant, viewer, processor, displayList,
                            fullProposalCount, saved, resetToFirst, restoreAfterFilterToggle, true,
                            true);
                    }
                    catch (Exception e)
                    {
                        ContentAssistDebug.log("popupSync apply ERROR: " + e.getMessage()); //$NON-NLS-1$
                    }
                });
            }
            else
            {
                try
                {
                    applyPopupListSync(popup, assistant, viewer, processor, displayList,
                        fullProposalCount, saved, resetToFirst, restoreAfterFilterToggle,
                        !smartEnabled, false);
                }
                catch (Exception e)
                {
                    ContentAssistDebug.log("popupSync apply ERROR: " + e.getMessage()); //$NON-NLS-1$
                }
            }

            finishSyncCycle(popup);
            return true;
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("popupSync ERROR: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
        finally
        {
            if (viewer != null && isPopupVisible(assistant))
                ContentAssistPopupUi.updateContextTypeLabel(viewer);
            RECOMPUTE_GUARD.set(Boolean.FALSE);
            ContentAssistSessionReloader.flushPendingPopupRefreshIfAny();
        }
    }

    private static void applyPopupListSync(Object popup, ContentAssistant assistant,
                                           SourceViewer viewer,
                                           SmartContentAssistProcessor processor,
                                           List<ICompletionProposal> displayList,
                                           int fullCount, SavedSelection saved,
                                           boolean resetToFirst, boolean restoreAfterFilterToggle,
                                           boolean runStockFilterAfter,
                                           boolean deferRestoreUntilStockFilter) throws Exception
    {
        boolean restoreNow = restoreAfterFilterToggle && !deferRestoreUntilStockFilter;
        setProposalsAndRestoreSelection(popup, displayList, false, saved, resetToFirst, restoreNow);

        List<ICompletionProposal> applied = (List<ICompletionProposal>)
            fFilteredProposalsField.get(popup);
        if (fComputedProposalsField != null && applied != null)
            fComputedProposalsField.set(popup, applied);

        installPopupScrollWatcher(popup, assistant, viewer, processor);

        int tableRows = tableItemCount(popup);
        logSyncResult(fullCount, displayList.size(), tableRows,
            SmartFilterTracker.getCurrentFilter(), resetToFirst);

        if (tableRows >= 0 && tableRows != displayList.size() && !displayList.isEmpty())
            forceTableRefresh(popup, displayList, saved, resetToFirst, restoreNow);

        if (runStockFilterAfter)
            runStockFilterRunnable(assistant);

        if (deferRestoreUntilStockFilter && restoreAfterFilterToggle)
        {
            applied = (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            restoreSelection(popup, saved, applied);
        }
    }

    private static void runWithPopupRedrawSuppressed(Object popup, Runnable task)
    {
        org.eclipse.swt.widgets.Shell shell = getProposalShell(popup);
        Table table = getProposalTable(popup);
        if (shell != null && !shell.isDisposed())
            shell.setRedraw(false);
        if (table != null && !table.isDisposed())
            table.setRedraw(false);
        try
        {
            task.run();
        }
        finally
        {
            if (table != null && !table.isDisposed())
                table.setRedraw(true);
            if (shell != null && !shell.isDisposed())
                shell.setRedraw(true);
        }
    }

    /** Debounced пересчёт при смене каретки без правки документа (клик, стрелки). */
    public static void scheduleRecomputeOnCaretChange(ContentAssistant assistant, SourceViewer viewer,
                                                      SmartContentAssistProcessor processor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (assistant == null || viewer == null || processor == null)
            return;
        if (!isPopupVisible(assistant))
            return;
        Control widget = viewer.getTextWidget() != null ? (Control) viewer.getTextWidget() : null;
        if (widget == null || widget.isDisposed())
            return;
        Display display = widget.getDisplay();
        if (display == null || display.isDisposed())
            return;

        PendingDebouncedFilter prev = PENDING_CARET_TASKS.remove(viewer);
        if (prev != null)
            prev.display.timerExec(-1, prev.task);

        final int taskGen = CARET_TASK_GEN.incrementAndGet();
        Runnable task = () -> {
            PENDING_CARET_TASKS.remove(viewer);
            if (widget.isDisposed() || taskGen != CARET_TASK_GEN.get()
                || isRecomputeInProgress())
                return;
            if (!isPopupVisible(assistant))
                return;
            try
            {
                int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
                IDocument doc = viewer.getDocument();
                if (caret >= 0)
                    SmartContentAssistProcessor.primeFilterTrackerOnly(viewer, caret);
                if (shouldClosePopupAtCaret(viewer, caret))
                {
                    hideProposalPopup(assistant);
                    return;
                }
                if (shouldRecomputePopupList(SmartFilterTracker.getCurrentFilter(), false,
                    doc, caret))
                {
                    runStockFilterRunnable(assistant);
                    if (SmartAssistFilterState.isSmartFilterEnabled())
                        recomputePopupList(assistant, viewer, processor);
                }
                else
                    runStockFilterRunnable(assistant);
            }
            catch (Exception e)
            {
                ContentAssistDebug.log("caret debounce ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        };
        PENDING_CARET_TASKS.put(viewer, new PendingDebouncedFilter(task, display));
        display.timerExec(FILTER_DEBOUNCE_MS, task);
    }

    public static void cancelCaretRecompute(SourceViewer viewer)
    {
        if (viewer == null)
            return;
        CARET_TASK_GEN.incrementAndGet();
        PendingDebouncedFilter pending = PENDING_CARET_TASKS.remove(viewer);
        if (pending != null && pending.display != null && !pending.display.isDisposed())
            pending.display.timerExec(-1, pending.task);
    }

    /** Сбрасывает pending filter/document и отменяет отложенный debounce (разрыв цикла). */
    private static void finishSyncCycle(Object popup)
    {
        clearPendingDocumentEvents(popup);
        clearFilterPending(popup);
        cancelDebouncedNativeFilter(popup);
        FILTER_TASK_GEN.incrementAndGet();
    }

    // ---- logging ------------------------------------------------------------

    private static void installPopupScrollWatcher(Object popup, ContentAssistant assistant,
                                                  SourceViewer viewer,
                                                  SmartContentAssistProcessor processor)
    {
        if (popup == null || POPUP_SCROLL_LISTENERS.containsKey(popup))
            return;
        try
        {
            initPopupReflection(popup);
            Table table = getProposalTable(popup);
            if (table == null || table.isDisposed())
                return;
            Listener listener = event -> maybeLoadMoreOnScroll(popup, assistant, viewer,
                processor, table);
            table.addListener(SWT.Selection, listener);
            POPUP_SCROLL_LISTENERS.put(popup, listener);
            FilterListMouseCurrentSync.installForTable(table,
                idx -> isRecomputeInProgress(),
                idx -> {
                    try
                    {
                        selectProposalAtIndex(popup, idx);
                    }
                    catch (Exception ignored) {}
                });
            ListSelectionThemeHook.ensureControl(table);
        }
        catch (Exception ignored) {}
    }

    private static void uninstallPopupScrollWatcher(Object popup)
    {
        Listener listener = POPUP_SCROLL_LISTENERS.remove(popup);
        try
        {
            Table table = getProposalTable(popup);
            if (table != null && !table.isDisposed())
            {
                if (listener != null)
                    table.removeListener(SWT.Selection, listener);
                FilterListMouseCurrentSync.uninstall(table);
            }
        }
        catch (Exception ignored) {}
    }

    private static void maybeLoadMoreOnScroll(Object popup, ContentAssistant assistant,
                                              SourceViewer viewer,
                                              SmartContentAssistProcessor processor, Table table)
    {
        if (isRecomputeInProgress() || assistant == null || viewer == null || processor == null)
            return;
        if (table == null || table.isDisposed())
            return;
        int index = table.getSelectionIndex();
        int shown = table.getItemCount();
        if (index < 0 || shown <= 0 || index < shown - POPUP_LOAD_MORE_MARGIN)
            return;

        boolean needRecompute = false;
        if (popupDisplayLimit < lastFullFilteredCount)
        {
            popupDisplayLimit = Math.min(lastFullFilteredCount,
                popupDisplayLimit + POPUP_DISPLAY_STEP);
            needRecompute = true;
            ContentAssistDebug.log("popupSync loadMore shownLimit=" + popupDisplayLimit //$NON-NLS-1$
                + " total=" + lastFullFilteredCount); //$NON-NLS-1$
        }
        else if (!processor.isFullListComplete()
            && SmartFilterTracker.getCurrentFilter().isEmpty())
        {
            processor.scheduleLoadFullListCache(viewer);
        }
        if (needRecompute)
            recomputePopupList(assistant, viewer, processor);
    }

    /** Логирует результат синхронизации таблицы. */
    private static void logSyncResult(int proposalCount, int shownCount, int tableRows,
                                      String filter, boolean resetToFirst)
    {
        if (!ContentAssistDebug.isEnabled())
            return;
        if (tableRows >= 0 && tableRows != shownCount)
        {
            ContentAssistDebug.log("popupSync MISMATCH filter=\"" + filter //$NON-NLS-1$
                + "\" expected=" + shownCount + " total=" + proposalCount //$NON-NLS-1$ //$NON-NLS-2$
                + " tableRows=" + tableRows + " resetSel=" + resetToFirst); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- helpers ------------------------------------------------------------

    public static void hideStatusLine(ContentAssistant assistant, Object popup)
    {
        try
        {
            if (assistant != null)
                assistant.setStatusLineVisible(false);
            if (popup == null)
                return;
            initPopupReflection(popup);
            if (setStatusLineVisibleMethod != null)
                setStatusLineVisibleMethod.invoke(popup, Boolean.FALSE);
            if (fMessageTextField != null)
            {
                Label message = (Label) fMessageTextField.get(popup);
                if (message != null && !message.isDisposed())
                    message.dispose();
                fMessageTextField.set(popup, null);
            }
        }
        catch (Exception ignored) {}
    }

    private static void forceTableRefresh(Object popup, List<ICompletionProposal> list,
                                          SavedSelection saved, boolean resetToFirst,
                                          boolean restoreAfterFilterToggle)
            throws Exception
    {
        if (fProposalTableField == null || selectProposalMethod == null || list == null)
            return;
        Table table = (Table) fProposalTableField.get(popup);
        if (table == null || table.isDisposed())
            return;
        fFilteredProposalsField.set(popup, list);
        table.clearAll();
        table.setItemCount(list.size());
        applySelection(popup, list, saved, resetToFirst, restoreAfterFilterToggle);
    }

    private static boolean shouldResetSelectionToFirst()
    {
        return SmartAssistFilterState.isSmartFilterEnabled()
            && !SmartFilterTracker.getCurrentFilter().isEmpty();
    }

    private static int tableItemCount(Object popup)
    {
        try
        {
            if (fProposalTableField == null)
                return -1;
            Table table = (Table) fProposalTableField.get(popup);
            if (table == null || table.isDisposed())
                return -1;
            return table.getItemCount();
        }
        catch (Exception ignored)
        {
            return -1;
        }
    }

    private static boolean isShellVisible(Object popup)
    {
        try
        {
            if (fProposalShellField == null)
                return false;
            Object shell = fProposalShellField.get(popup);
            return shell instanceof org.eclipse.swt.widgets.Shell
                && !((org.eclipse.swt.widgets.Shell) shell).isDisposed()
                && ((org.eclipse.swt.widgets.Shell) shell).isVisible();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    public static void refreshAdditionalInfo(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup != null)
                refreshAdditionalInfo(popup, false);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("additionalInfo ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Штатное обновление боковой панели assist при смене строки — только
     * {@code handleTableSelectionChanged}, без {@code configureAndMakeVisible}.
     */
    public static void notifyAdditionalInfoSelectionChanged(ContentAssistant assistant)
    {
        if (assistant == null)
            return;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            initPopupReflection(popup);
            if (fAdditionalInfoControllerField == null || handleTableSelectionChangedMethod == null)
                return;
            Object controller = fAdditionalInfoControllerField.get(popup);
            if (controller != null)
                handleTableSelectionChangedMethod.invoke(controller);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("notifyAdditionalInfoSelectionChanged: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * ИР: штатный путь боковой подсказки — как EDT {@link #pinMergedAdditionalInfo}.
     *
     * @return {@code true} если proposal найден и {@code showInformation} вызван
     */
    public static boolean pinIrSideHint(
        ContentAssistant assistant, IrCompletionProposal ir, Object html)
    {
        if (assistant == null || ir == null || html == null)
            return false;
        try
        {
            Object controller = resolveAdditionalInfoController(assistant);
            if (controller == null)
                return false;
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return false;
            initPopupReflection(popup);
            ICompletionProposal proposal = resolveWrappedProposalForIr(popup, ir);
            if (proposal == null)
                return false;
            if (additionalInfoShowInformationMethod == null)
            {
                additionalInfoShowInformationMethod = controller.getClass().getDeclaredMethod(
                    "showInformation", ICompletionProposal.class, Object.class); //$NON-NLS-1$
                additionalInfoShowInformationMethod.setAccessible(true);
            }
            additionalInfoShowInformationMethod.invoke(controller, proposal, html);
            scheduleAssistBrowserHtmlApply(assistant, html);
            return true;
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("pinIrSideHint: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static ICompletionProposal resolveWrappedProposalForIr(
        Object popup, IrCompletionProposal ir)
    {
        if (popup == null || ir == null || fFilteredProposalsField == null)
            return null;
        try
        {
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null || list.isEmpty())
                return null;
            String cacheKey = ir.getStableCacheKey();
            if (cacheKey != null && !cacheKey.isEmpty())
            {
                for (ICompletionProposal proposal : list)
                {
                    if (proposal == null)
                        continue;
                    ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
                    if (raw instanceof IrCompletionProposal irRow
                        && cacheKey.equals(irRow.getStableCacheKey()))
                        return proposal;
                }
            }
            String display = ir.getDisplayString();
            if (display != null && !display.isEmpty())
            {
                for (ICompletionProposal proposal : list)
                {
                    if (proposal != null && display.equals(proposal.getDisplayString()))
                        return proposal;
                }
            }
            Table table = getProposalTable(popup);
            if (table != null && !table.isDisposed())
            {
                int idx = table.getSelectionIndex();
                if (idx >= 0 && idx < list.size())
                {
                    ICompletionProposal selected = list.get(idx);
                    ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(selected);
                    if (raw == ir)
                        return selected;
                }
            }
            return null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static void syncProposalSelectionOnly(Object popup, boolean resetToFirst)
            throws Exception
    {
        initPopupReflection(popup);

        int selectionIndex = 0;
        if (!resetToFirst && fProposalTableField != null)
        {
            Table table = (Table) fProposalTableField.get(popup);
            if (table != null && !table.isDisposed())
            {
                int idx = table.getSelectionIndex();
                if (idx >= 0)
                    selectionIndex = idx;
            }
        }

        if (fProposalTableField != null)
        {
            Table table = (Table) fProposalTableField.get(popup);
            if (table != null && !table.isDisposed() && table.getItemCount() > 0)
            {
                if (selectionIndex >= table.getItemCount())
                    selectionIndex = table.getItemCount() - 1;
                table.setSelection(selectionIndex);
                table.showSelection();
            }
        }

        if (selectProposalMethod != null)
            selectProposalMethod.invoke(popup, selectionIndex, Boolean.FALSE);

        if (fAdditionalInfoControllerField != null && handleTableSelectionChangedMethod != null)
        {
            Object controller = fAdditionalInfoControllerField.get(popup);
            if (controller != null)
                handleTableSelectionChangedMethod.invoke(controller);
        }
    }

    private static void refreshAdditionalInfo(Object popup, boolean resetToFirst)
            throws Exception
    {
        syncProposalSelectionOnly(popup, resetToFirst);

        if (configureAndMakeVisibleMethod != null && fProposalShellField != null)
        {
            Object shell = fProposalShellField.get(popup);
            if (shell instanceof org.eclipse.swt.widgets.Shell
                && !((org.eclipse.swt.widgets.Shell) shell).isDisposed()
                && ((org.eclipse.swt.widgets.Shell) shell).isVisible())
            {
                configureAndMakeVisibleMethod.invoke(popup);
            }
        }
    }

    private static void scheduleDebouncedNativeFilter(Object popup, SourceViewer viewer,
                                                      Runnable original)
    {
        Control widget = viewer != null ? (Control) viewer.getTextWidget() : null;
        if (widget == null || widget.isDisposed())
            return;
        Display display = widget.getDisplay();
        if (display == null || display.isDisposed())
            return;

        PendingDebouncedFilter prev = PENDING_FILTER_TASKS.remove(popup);
        if (prev != null)
            prev.display.timerExec(-1, prev.task);

        final int taskGen = FILTER_TASK_GEN.incrementAndGet();
        Runnable task = () -> {
            PENDING_FILTER_TASKS.remove(popup);
            if (widget.isDisposed() || taskGen != FILTER_TASK_GEN.get()
                || isRecomputeInProgress())
                return;
            try
            {
                primeAssistFilterContext(viewer);
                ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
                SmartContentAssistProcessor processor =
                    ContentAssistSessionReloader.getActiveProcessor();
                int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
                IDocument doc = viewer.getDocument();
                if (assistant != null && processor != null
                    && shouldRecomputePopupList(SmartFilterTracker.getCurrentFilter(), false,
                        doc, caret))
                {
                    runStockFilterRunnable(assistant);
                    if (SmartAssistFilterState.isSmartFilterEnabled())
                        recomputePopupList(assistant, viewer, processor);
                }
                else
                    runStockFilterRunnable(assistant);
            }
            catch (Exception e)
            {
                ContentAssistDebug.log("filter debounce ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        };
        PENDING_FILTER_TASKS.put(popup, new PendingDebouncedFilter(task, display));
        display.timerExec(FILTER_DEBOUNCE_MS, task);
    }

    private static void cancelDebouncedNativeFilter(Object popup)
    {
        if (popup == null)
            return;
        PendingDebouncedFilter pending = PENDING_FILTER_TASKS.remove(popup);
        if (pending != null && pending.display != null && !pending.display.isDisposed())
            pending.display.timerExec(-1, pending.task);
    }

    private static void ensureFilterPending(Object popup) throws Exception
    {
        if (fIsFilterPendingField == null)
            return;
        AtomicBoolean pending = (AtomicBoolean) fIsFilterPendingField.get(popup);
        if (pending != null)
            pending.set(true);
    }

    /**
     * Пересчёт при непустом фильтре, toggle smart-фильтра или member-access ({@code obj.}).
     */
    private static boolean shouldRecomputePopupList(String filter, boolean afterFilterToggle,
                                                    IDocument doc, int caret)
    {
        boolean memberAccess = doc != null && caret >= 0
            && SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(doc, caret) >= 0;
        boolean memberRecompute = memberAccess && (filter == null || filter.isEmpty());
        return afterFilterToggle
            || (filter != null && !filter.isEmpty())
            || memberRecompute;
    }

    /**
     * Штатный {@code fFilterRunnable}: offset'ы popup, {@code completeCommonPrefix}, validate.
     * Вызывать до нашего {@link #recomputePopupList}, если тот нужен.
     */
    static void runStockFilterRunnable(ContentAssistant assistant)
    {
        if (assistant == null)
            return;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            Runnable original = ORIGINAL_FILTER_RUNNABLES.get(popup);
            if (original == null)
                return;
            initPopupReflection(popup);
            ensureFilterPending(popup);
            original.run();
            if (!SmartAssistFilterState.isSmartFilterEnabled())
                applyPrefixSelectionIfNeeded(assistant);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("stockFilter ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** После штатного filter runnable при выключенном smart — вернуть активацию на строку с набранным префиксом. */
    private static void applyPrefixSelectionIfNeeded(ContentAssistant assistant)
    {
        String prefix = SmartFilterTracker.getCurrentFilter();
        if (prefix == null || prefix.isEmpty())
            return;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null || list.isEmpty())
                return;
            int idx = findFirstPrefixMatchIndex(list, prefix);
            if (idx >= 0)
                selectProposalAtIndex(popup, idx);
        }
        catch (Exception ignored) {}
    }

    /** Каретка перед уже существующими {@code (} — штатная вставка ломается, закрываем assist. */
    static boolean shouldClosePopupAtCaret(SourceViewer viewer, int caret)
    {
        if (viewer == null || caret < 0)
            return false;
        IDocument doc = viewer.getDocument();
        if (doc == null || caret >= doc.getLength())
            return false;
        try
        {
            return doc.getChar(caret) == '(';
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static void primeAssistFilterContext(SourceViewer viewer)
    {
        try
        {
            if (viewer == null || viewer.getTextWidget() == null
                || viewer.getTextWidget().isDisposed())
                return;
            int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
            if (caret < 0)
                return;
            SmartContentAssistProcessor.primeFilterTrackerOnly(viewer, caret);
        }
        catch (Exception ignored) {}
    }

    private static void clearFilterPending(Object popup)
    {
        try
        {
            if (fIsFilterPendingField == null)
                return;
            AtomicBoolean pending = (AtomicBoolean) fIsFilterPendingField.get(popup);
            if (pending != null)
                pending.set(false);
        }
        catch (Exception ignored) {}
    }

    public static Object getPopupObject(ContentAssistant assistant)
    {
        try { return getPopup(assistant); }
        catch (Exception ignored) { return null; }
    }

    /** Начало вводимого идентификатора ({@code fFilterOffset}); не использовать для префикса. */
    public static int getFilterOffset(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return -1;
            initPopupReflection(popup);
            if (fFilterOffsetField == null)
                return -1;
            return fFilterOffsetField.getInt(popup);
        }
        catch (Exception ignored) { return -1; }
    }

    /** Позиция вызова assist ({@code fInvocationOffset}) — каретка на момент Ctrl+Space. */
    public static int getInvocationOffset(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return -1;
            initPopupReflection(popup);
            if (fInvocationOffsetField == null)
                return -1;
            return fInvocationOffsetField.getInt(popup);
        }
        catch (Exception ignored) { return -1; }
    }

    /** Каретка при последнем completion ({@code fLastCompletionOffset}) — для префикса. */
    public static int getLastCompletionOffset(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return -1;
            initPopupReflection(popup);
            if (fLastCompletionOffsetField == null)
                return -1;
            return fLastCompletionOffsetField.getInt(popup);
        }
        catch (Exception ignored) { return -1; }
    }

    public static boolean isPopupVisible(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return false;
            initPopupReflection(popup);
            if (fProposalShellField == null)
                return false;
            Object shell = fProposalShellField.get(popup);
            return shell instanceof org.eclipse.swt.widgets.Shell
                && !((org.eclipse.swt.widgets.Shell) shell).isDisposed()
                && ((org.eclipse.swt.widgets.Shell) shell).isVisible();
        }
        catch (Exception ignored) { return false; }
    }

    public static org.eclipse.swt.widgets.Shell getProposalShell(Object popup)
    {
        try
        {
            if (popup == null)
                return null;
            initPopupReflection(popup);
            if (fProposalShellField == null)
                return null;
            Object shell = fProposalShellField.get(popup);
            return shell instanceof org.eclipse.swt.widgets.Shell
                ? (org.eclipse.swt.widgets.Shell) shell : null;
        }
        catch (Exception ignored) { return null; }
    }

    public static Table getProposalTable(Object popup)
    {
        try
        {
            if (popup == null)
                return null;
            initPopupReflection(popup);
            if (fProposalTableField == null)
                return null;
            Object table = fProposalTableField.get(popup);
            return table instanceof Table ? (Table) table : null;
        }
        catch (Exception ignored) { return null; }
    }

    /** Перерисовка строк списка после дорасчёта типа ИР. */
    public static void refreshProposalTable(ContentAssistant assistant)
    {
        if (assistant == null)
            return;
        try
        {
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return;
            initPopupReflection(popup);
            Table table = getProposalTable(popup);
            if (table == null || table.isDisposed())
                return;
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list = fFilteredProposalsField != null
                ? (List<ICompletionProposal>) fFilteredProposalsField.get(popup) : null;
            int idx = table.getSelectionIndex();
            if (list != null && !list.isEmpty())
            {
                table.clearAll();
                table.setItemCount(list.size());
                if (idx >= 0 && idx < list.size() && selectProposalMethod != null)
                    selectProposalMethod.invoke(popup, idx, Boolean.FALSE);
            }
            table.redraw();
            table.update();
        }
        catch (Exception ignored) {}
    }

    /** Активирована ли строка с тем же {@link IrCompletionProposal} (стабильный ключ; Eclipse {@code selected}). */
    public static boolean isSelectedIrProposal(
        ContentAssistant assistant, IrCompletionProposal ir)
    {
        if (assistant == null || ir == null)
            return false;
        try
        {
            Object popup = getPopupObject(assistant);
            if (popup == null || fFilteredProposalsField == null)
                return false;
            Table table = getProposalTable(popup);
            if (table == null || table.isDisposed())
                return false;
            int idx = table.getSelectionIndex();
            if (idx < 0)
                return false;
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null || idx >= list.size())
                return false;
            ICompletionProposal selected = list.get(idx);
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(selected);
            if (!(raw instanceof IrCompletionProposal irRow))
                return false;
            String cacheKey = ir.getStableCacheKey();
            return cacheKey != null && cacheKey.equals(irRow.getStableCacheKey());
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static Object getPopup(ContentAssistant assistant) throws Exception
    {
        if (popupField == null)
        {
            popupField = ContentAssistant.class.getDeclaredField("fProposalPopup"); //$NON-NLS-1$
            popupField.setAccessible(true);
        }
        return popupField.get(assistant);
    }

    private static void initPopupReflection(Object popup) throws Exception
    {
        if (setProposalsMethod != null)
            return;

        Class<?> popupClass = popup.getClass();
        setProposalsMethod =
            popupClass.getDeclaredMethod("setProposals", List.class, boolean.class); //$NON-NLS-1$
        setProposalsMethod.setAccessible(true);
        selectProposalMethod =
            popupClass.getDeclaredMethod("selectProposal", int.class, boolean.class); //$NON-NLS-1$
        selectProposalMethod.setAccessible(true);
        fComputedProposalsField = popupClass.getDeclaredField("fComputedProposals"); //$NON-NLS-1$
        fComputedProposalsField.setAccessible(true);
        fFilteredProposalsField = popupClass.getDeclaredField("fFilteredProposals"); //$NON-NLS-1$
        fFilteredProposalsField.setAccessible(true);
        fIsFilteredSubsetField = popupClass.getDeclaredField("fIsFilteredSubset"); //$NON-NLS-1$
        fIsFilteredSubsetField.setAccessible(true);
        fIsFilterPendingField = popupClass.getDeclaredField("fIsFilterPending"); //$NON-NLS-1$
        fIsFilterPendingField.setAccessible(true);
        fFilterRunnableField = popupClass.getDeclaredField("fFilterRunnable"); //$NON-NLS-1$
        fFilterRunnableField.setAccessible(true);
        fProposalTableField = popupClass.getDeclaredField("fProposalTable"); //$NON-NLS-1$
        fProposalTableField.setAccessible(true);
        fProposalShellField = popupClass.getDeclaredField("fProposalShell"); //$NON-NLS-1$
        fProposalShellField.setAccessible(true);
        fAdditionalInfoControllerField =
            popupClass.getDeclaredField("fAdditionalInfoController"); //$NON-NLS-1$
        fAdditionalInfoControllerField.setAccessible(true);
        configureAndMakeVisibleMethod =
            popupClass.getDeclaredMethod("configureAndMakeVisible"); //$NON-NLS-1$
        configureAndMakeVisibleMethod.setAccessible(true);
        Class<?> controllerClass = Class.forName(
            "org.eclipse.jface.text.contentassist.AdditionalInfoController"); //$NON-NLS-1$
        handleTableSelectionChangedMethod =
            controllerClass.getDeclaredMethod("handleTableSelectionChanged"); //$NON-NLS-1$
        handleTableSelectionChangedMethod.setAccessible(true);
        try
        {
            fAdditionalInfoProposalField = controllerClass.getDeclaredField("fProposal"); //$NON-NLS-1$
            fAdditionalInfoProposalField.setAccessible(true);
            fAdditionalInfoInformationField = controllerClass.getDeclaredField("fInformation"); //$NON-NLS-1$
            fAdditionalInfoInformationField.setAccessible(true);
        }
        catch (NoSuchFieldException ignored) {}
        try
        {
            fMessageTextField = popupClass.getDeclaredField("fMessageText"); //$NON-NLS-1$
            fMessageTextField.setAccessible(true);
            setStatusLineVisibleMethod =
                popupClass.getDeclaredMethod("setStatusLineVisible", boolean.class); //$NON-NLS-1$
            setStatusLineVisibleMethod.setAccessible(true);
        }
        catch (NoSuchFieldException | NoSuchMethodException ignored) {}
        try
        {
            fFilterOffsetField = popupClass.getDeclaredField("fFilterOffset"); //$NON-NLS-1$
            fFilterOffsetField.setAccessible(true);
            fLastCompletionOffsetField =
                popupClass.getDeclaredField("fLastCompletionOffset"); //$NON-NLS-1$
            fLastCompletionOffsetField.setAccessible(true);
            fInvocationOffsetField = popupClass.getDeclaredField("fInvocationOffset"); //$NON-NLS-1$
            fInvocationOffsetField.setAccessible(true);
        }
        catch (Exception ignored) {}
        try
        {
            fDocumentEventsField = popupClass.getDeclaredField("fDocumentEvents"); //$NON-NLS-1$
            fDocumentEventsField.setAccessible(true);
            hidePopupMethod = popupClass.getDeclaredMethod("hide"); //$NON-NLS-1$
            hidePopupMethod.setAccessible(true);
        }
        catch (NoSuchFieldException | NoSuchMethodException ignored) {}
    }

    public static void hideProposalPopup(Object popup)
    {
        try
        {
            if (popup == null)
                return;
            initPopupReflection(popup);
            if (hidePopupMethod != null)
                hidePopupMethod.invoke(popup);
            ContentAssistDebug.log("popupSync closed"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("popupSync close ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static void hideProposalPopup(ContentAssistant assistant)
    {
        try { hideProposalPopup(getPopup(assistant)); }
        catch (Exception ignored) {}
    }

    public static Object resolveAdditionalInfoController(ContentAssistant assistant)
    {
        if (assistant == null)
            return null;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return null;
            initPopupReflection(popup);
            if (fAdditionalInfoControllerField == null)
                return null;
            return fAdditionalInfoControllerField.get(popup);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    public static IXtextBrowserInformationControl resolveVisibleBrowserControl(ContentAssistant assistant)
    {
        Object controller = resolveAdditionalInfoController(assistant);
        if (controller == null)
            return null;
        Object control = Global.invoke(controller, "getCurrentInformationControl2"); //$NON-NLS-1$
        return control instanceof IXtextBrowserInformationControl browser ? browser : null;
    }

    private static Method additionalInfoShowInformationMethod;

    /** Creator браузера из EDT-строк того же popup (ИР-строки его не задают). */
    public static IInformationControlCreator findAssistBrowserCreatorInPopup(
        ContentAssistant assistant)
    {
        if (assistant == null)
            return null;
        try
        {
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return null;
            initPopupReflection(popup);
            if (fFilteredProposalsField == null)
                return null;
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null)
                return null;
            for (ICompletionProposal proposal : list)
            {
                if (proposal == null)
                    continue;
                ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
                if (raw instanceof IrCompletionProposal)
                    continue;
                if (raw instanceof ICompletionProposalExtension3 ext3)
                {
                    IInformationControlCreator creator = ext3.getInformationControlCreator();
                    if (creator != null)
                        return creator;
                }
            }
        }
        catch (Exception ignored) {}
        return null;
    }

    /**
     * ИР-активация (fallback): {@link #refreshAdditionalInfo} + опционально {@code setText} в browser.
     * Основной путь — {@link #pinIrSideHint}.
     */
    public static void publishIrActivationSideHint(
        ContentAssistant assistant, Object mergedInfo, String cacheKey)
    {
        if (assistant == null || mergedInfo == null)
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null && cacheKey != null)
        {
            if (reloader.isIrSideHintPublishedForKey(cacheKey))
                return;
            reloader.markIrSideHintPublished(cacheKey);
        }
        refreshAdditionalInfo(assistant);
        String html = IrBslHoverHtml.readHtml(mergedInfo);
        boolean styledDoc = IrBslHoverHtml.isFullHtmlDocument(html);
        if (!styledDoc)
            scheduleAssistBrowserHtmlApply(assistant, mergedInfo);
    }

    /** Подменяет {@code fInformation} до штатного {@code showInformation} контроллера. */
    public static void patchAdditionalInfoPayload(ContentAssistant assistant, Object payload)
    {
        if (assistant == null || payload == null)
            return;
        try
        {
            Object controller = resolveAdditionalInfoController(assistant);
            if (controller == null || fAdditionalInfoInformationField == null)
                return;
            fAdditionalInfoInformationField.set(controller, payload);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("patchAdditionalInfoPayload: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** Повторно показывает merged HTML через штатный AdditionalInfoController. */
    public static void pinMergedAdditionalInfo(
        ContentAssistant assistant, String displayKey, Object mergedInfo)
    {
        if (assistant == null || mergedInfo == null)
            return;
        try
        {
            Object controller = resolveAdditionalInfoController(assistant);
            if (controller == null)
                return;
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return;
            initPopupReflection(popup);
            ICompletionProposal proposal = resolveProposalByDisplay(popup, displayKey);
            if (proposal == null)
                return;
            if (additionalInfoShowInformationMethod == null)
            {
                additionalInfoShowInformationMethod = controller.getClass().getDeclaredMethod(
                    "showInformation", ICompletionProposal.class, Object.class); //$NON-NLS-1$
                additionalInfoShowInformationMethod.setAccessible(true);
            }
            additionalInfoShowInformationMethod.invoke(controller, proposal, mergedInfo);
            scheduleAssistBrowserHtmlApply(assistant, mergedInfo);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("pinMergedAdditionalInfo: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** Browser assist для ИР-строк создаётся с задержкой — повторяем setText. */
    private static void scheduleAssistBrowserHtmlApply(ContentAssistant assistant, Object mergedInfo)
    {
        String rawHtml = IrBslHoverHtml.readHtml(mergedInfo);
        if (rawHtml == null || rawHtml.isEmpty())
            return;
        boolean rawFullDoc = IrBslHoverHtml.isFullHtmlDocument(rawHtml);
        String displayHtml = rawFullDoc ? rawHtml : IrBslHoverHtml.wrapForAssistBrowser(rawHtml);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        final int[] attempts = {0};
        Runnable task = new Runnable() {
            @Override
            public void run()
            {
                if (display.isDisposed() || assistant == null)
                    return;
                IXtextBrowserInformationControl browser = resolveVisibleBrowserControl(assistant);
                boolean applied = browser != null
                    && IrBslHoverHtml.applyHtmlToControl(browser, displayHtml);
                if (!applied && attempts[0] < 8)
                {
                    attempts[0]++;
                    display.timerExec(40, this);
                }
            }
        };
        display.asyncExec(task);
    }

    private static ICompletionProposal resolveProposalByDisplay(Object popup, String displayKey)
    {
        if (popup == null || fFilteredProposalsField == null)
            return null;
        try
        {
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null)
                return null;
            if (displayKey != null)
            {
                for (ICompletionProposal proposal : list)
                {
                    if (proposal != null && displayKey.equals(proposal.getDisplayString()))
                        return proposal;
                }
            }
            Table table = getProposalTable(popup);
            if (table != null && !table.isDisposed())
            {
                int idx = table.getSelectionIndex();
                if (idx >= 0 && idx < list.size())
                    return list.get(idx);
            }
        }
        catch (Exception ignored) {}
        return null;
    }

    public static boolean isSelectedDisplay(ContentAssistant assistant, String displayKey)
    {
        // Активная строка assist (Eclipse selected), не подтверждение apply.
        if (assistant == null || displayKey == null || displayKey.isEmpty())
            return false;
        try
        {
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return false;
            initPopupReflection(popup);
            Table table = getProposalTable(popup);
            if (table == null || table.isDisposed())
                return false;
            int idx = table.getSelectionIndex();
            if (idx < 0 || fFilteredProposalsField == null)
                return false;
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null || idx >= list.size())
                return false;
            ICompletionProposal selected = list.get(idx);
            return selected != null && displayKey.equals(selected.getDisplayString());
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public static boolean isShowingProposal(ContentAssistant assistant, ICompletionProposal proposal)
    {
        if (assistant == null || proposal == null)
            return false;
        try
        {
            Object controller = resolveAdditionalInfoController(assistant);
            if (controller == null || fAdditionalInfoProposalField == null)
                return false;
            Object current = fAdditionalInfoProposalField.get(controller);
            if (!(current instanceof ICompletionProposal currentProposal))
                return false;
            ICompletionProposal expected = SmartContentAssistProcessor.unwrapProposal(proposal);
            ICompletionProposal actual = SmartContentAssistProcessor.unwrapProposal(currentProposal);
            if (expected == null || actual == null)
                return false;
            String d1 = expected.getDisplayString();
            String d2 = actual.getDisplayString();
            return d1 != null && d1.equals(d2);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static boolean shouldCloseOnPendingDocumentEvents(Object popup)
    {
        try
        {
            if (fDocumentEventsField == null)
                return false;
            @SuppressWarnings("unchecked")
            List<DocumentEvent> events = (List<DocumentEvent>) fDocumentEventsField.get(popup);
            if (events == null || events.isEmpty())
                return false;
            for (DocumentEvent event : events)
            {
                if (SmartContentAssistProcessor.shouldCloseAssistOnDocumentEvent(event))
                    return true;
            }
        }
        catch (Exception ignored) {}
        return false;
    }

    private static void clearPendingDocumentEvents(Object popup)
    {
        try
        {
            if (fDocumentEventsField == null)
                return;
            @SuppressWarnings("unchecked")
            List<DocumentEvent> events = (List<DocumentEvent>) fDocumentEventsField.get(popup);
            if (events != null)
                events.clear();
        }
        catch (Exception ignored) {}
    }

    public static int syncSessionOffsets(ContentAssistant assistant, SourceViewer viewer)
    {
        int caret = SmartContentAssistProcessor.resolveSessionCaret(assistant, viewer);
        if (caret < 0)
            caret = 0;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return caret;
            initPopupReflection(popup);
            clearFilterPending(popup);
            if (fIsFilteredSubsetField != null)
                fIsFilteredSubsetField.setBoolean(popup, false);
        }
        catch (Exception ignored) {}
        return caret;
    }

    public static final class SavedSelection
    {
        final int index;
        final String displayKey;

        SavedSelection(int index, String displayKey)
        {
            this.index = index;
            this.displayKey = displayKey;
        }
    }

    public static void captureSelectionBeforeFilterToggle(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            SavedSelection saved = saveSelectionForFilterToggle(popup);
            pendingFilterToggleSelection.set(saved);
        }
        catch (Exception ignored) {}
    }

    private static SavedSelection takePendingFilterToggleSelection()
    {
        SavedSelection saved = pendingFilterToggleSelection.get();
        pendingFilterToggleSelection.remove();
        return saved;
    }

    public static void clearPendingFilterToggleSelection()
    {
        pendingFilterToggleSelection.remove();
    }

    private static SavedSelection saveSelection(Object popup)
    {
        try
        {
            initPopupReflection(popup);
            Table table = getProposalTable(popup);
            int index = table != null && !table.isDisposed() ? table.getSelectionIndex() : -1;
            if (index < 0)
                index = 0;
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            String key = proposalDisplayKey(list, index);
            return new SavedSelection(index, key);
        }
        catch (Exception ignored)
        {
            return new SavedSelection(0, null);
        }
    }

    /** Перед toggle: приоритет элементу, совпадающему с набранным префиксом, не строке таблицы. */
    private static SavedSelection saveSelectionForFilterToggle(Object popup)
    {
        try
        {
            initPopupReflection(popup);
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            String prefix = SmartFilterTracker.getCurrentFilter();
            if (list != null && !list.isEmpty() && prefix != null && !prefix.isEmpty())
            {
                int prefixIdx = findFirstPrefixMatchIndex(list, prefix);
                if (prefixIdx >= 0)
                    return new SavedSelection(prefixIdx, proposalDisplayKey(list, prefixIdx));
            }
            return saveSelection(popup);
        }
        catch (Exception ignored)
        {
            return new SavedSelection(0, null);
        }
    }

    private static void setProposalsAndRestoreSelection(Object popup,
                                                        List<ICompletionProposal> list,
                                                        boolean filteredSubset,
                                                        SavedSelection saved,
                                                        boolean resetToFirst,
                                                        boolean restoreAfterFilterToggle)
            throws Exception
    {
        if (fComputedProposalsField != null)
            fComputedProposalsField.set(popup, list);
        setProposalsMethod.invoke(popup, list, filteredSubset);
        List<ICompletionProposal> applied =
            (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
        if (fComputedProposalsField != null && applied != null)
            fComputedProposalsField.set(popup, applied);
        applySelection(popup, applied != null ? applied : list, saved, resetToFirst,
            restoreAfterFilterToggle);
    }

    private static void applySelection(Object popup, List<ICompletionProposal> list,
                                       SavedSelection saved, boolean resetToFirst,
                                       boolean restoreAfterFilterToggle) throws Exception
    {
        if (list == null || list.isEmpty() || selectProposalMethod == null)
            return;
        if (resetToFirst)
            selectProposalAtIndex(popup, 0);
        else if (restoreAfterFilterToggle)
            restoreSelection(popup, saved, list);
        else if (!SmartAssistFilterState.isSmartFilterEnabled())
        {
            String prefix = SmartFilterTracker.getCurrentFilter();
            if (prefix != null && !prefix.isEmpty())
            {
                int idx = findFirstPrefixMatchIndex(list, prefix);
                selectProposalAtIndex(popup, idx >= 0 ? idx : 0);
            }
            else
                restoreSelection(popup, saved, list);
        }
        else
            restoreSelection(popup, saved, list);
    }

    private static void restoreSelection(Object popup, SavedSelection saved,
                                         List<ICompletionProposal> list) throws Exception
    {
        if (list == null || list.isEmpty() || selectProposalMethod == null)
            return;

        int index = findProposalIndex(list, saved);
        String prefix = SmartFilterTracker.getCurrentFilter();
        if (prefix != null && !prefix.isEmpty())
        {
            int prefixIdx = findFirstPrefixMatchIndex(list, prefix);
            if (prefixIdx >= 0)
            {
                boolean savedMatchesPrefix = index >= 0
                    && proposalNameStartsWithPrefix(list.get(index), prefix);
                if (!savedMatchesPrefix)
                    index = prefixIdx;
            }
        }
        if (index < 0 && saved != null && saved.index >= 0 && saved.index < list.size())
            index = saved.index;
        if (index < 0)
            index = 0;
        selectProposalAtIndex(popup, index);
    }

    private static int findProposalIndex(List<ICompletionProposal> list, SavedSelection saved)
    {
        if (saved == null || saved.displayKey == null || saved.displayKey.isEmpty())
            return -1;
        for (int i = 0; i < list.size(); i++)
        {
            if (saved.displayKey.equals(proposalDisplayKey(list, i)))
                return i;
        }
        return -1;
    }

    private static String proposalDisplayKey(List<ICompletionProposal> list, int index)
    {
        if (list == null || index < 0 || index >= list.size())
            return null;
        ICompletionProposal p = SmartContentAssistProcessor.unwrapProposal(list.get(index));
        return p != null ? p.getDisplayString() : null;
    }

    private static void selectProposalAtIndex(Object popup, int index) throws Exception
    {
        if (selectProposalMethod == null)
            return;
        Table table = getProposalTable(popup);
        if (table != null && !table.isDisposed() && table.getItemCount() > 0)
        {
            if (index < 0)
                index = 0;
            if (index >= table.getItemCount())
                index = table.getItemCount() - 1;
            table.setSelection(index);
            table.showSelection();
        }
        else if (index < 0)
            index = 0;
        selectProposalMethod.invoke(popup, index, Boolean.FALSE);
    }

    /** Первый элемент, чьё имя (до «(» / «:») начинается с префикса; список алфавитный. */
    private static int findFirstPrefixMatchIndex(List<ICompletionProposal> list, String prefix)
    {
        if (list == null || list.isEmpty() || prefix == null || prefix.isEmpty())
            return -1;
        for (int i = 0; i < list.size(); i++)
        {
            if (proposalNameStartsWithPrefix(list.get(i), prefix))
                return i;
        }
        return -1;
    }

    private static boolean proposalNameStartsWithPrefix(ICompletionProposal proposal, String prefix)
    {
        if (proposal == null || prefix == null || prefix.isEmpty())
            return false;
        ICompletionProposal p = SmartContentAssistProcessor.unwrapProposal(proposal);
        String display = p != null ? p.getDisplayString() : null;
        if (display == null || display.isEmpty())
            return false;
        String name = BslCompletionSideHintResolver.extractProposalName(display);
        if (name == null)
            return false;
        return name.length() >= prefix.length()
            && name.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
