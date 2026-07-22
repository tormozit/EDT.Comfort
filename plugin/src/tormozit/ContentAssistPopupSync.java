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
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlExtension5;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.xtext.ui.editor.hover.html.IXtextBrowserInformationControl;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Вспомогательные операции с {@code CompletionProposalPopup}.
 *
 * <p>При вводе символов — prepend к {@code fFilterRunnable} планирует debounced
 * {@link #recomputePopupList} (smart-фильтр) вместо {@code validate} по полному списку.
 *
 * <p>{@link #recomputePopupList} — toggle, ввод с фильтром, догрузка кэша.
 *
 * <h3>Регрессия: каретка после вставки {@code Метод();}</h3>
 * EDT ставит каретку внутрь {@code ()} через {@code BslDocumentListener.DataEvent}
 * (LinkedMode), зарегистрированный при штатном {@code createProposals}. Ключ map —
 * {@code replacementString}. Повторный {@code createProposals} / stock filter с другим
 * acceptor вызывает {@code reset} → {@code map.clear()} → LinkedMode не срабатывает,
 * JFace оставляет каретку в конце текста ({@code cursorPosition = length}).
 *
 * <p><b>Запрещено</b> на member-access (Ctrl+Space / session open), в т.ч. с префиксом
 * ({@code тз.Найти}): {@code runStockFilterRunnable} + {@link #recomputePopupList}.
 * Нужно: {@link #syncMemberAccessPopupInPlace} (collapse / refresh из кэша без stock)
 * + {@link SmartContentAssistProcessor#cancelDeferredDelegateComputes()}.
 *
 * <p>Проверка: без ИР и с ИР — auto и Ctrl+Space на {@code obj.} / {@code obj.Преф} →
 * каретка внутри {@code ()} у схлопнутого {@code Вставить();} / {@code Найти()}.
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
    private static int lastSyncedIrMergeGen = -1;
    private static final ThreadLocal<Boolean> RECOMPUTE_GUARD =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** fix12: session open literal no-IR — full smart recompute вместо literalStockOnly. */
    private static final ThreadLocal<Boolean> FORCE_SMART_LITERAL_OPEN = new ThreadLocal<>();
    /** H74/H75: sessionOpen | toggle | debounce */
    private static final ThreadLocal<String> RECOMPUTE_TRIGGER = new ThreadLocal<>();
    private static final AtomicInteger literalAutoSmartRouteLogs = new AtomicInteger();
    private static final IdentityHashMap<Object, Listener> POPUP_SCROLL_LISTENERS =
        new IdentityHashMap<>();
    private static final IdentityHashMap<SourceViewer, PendingDebouncedFilter> PENDING_CARET_TASKS =
        new IdentityHashMap<>();
    private static final int[] SESSION_POPUP_SYNC_DELAYS_MS = { 0, 30, 80, 150, 300 };
    private static final AtomicInteger SESSION_SYNC_GEN = new AtomicInteger();
    private static final IdentityHashMap<SourceViewer, Runnable> PENDING_SESSION_SYNC =
        new IdentityHashMap<>();
    private static final AtomicInteger FILTER_BAR_SYNC_GEN = new AtomicInteger();
    private static final IdentityHashMap<SourceViewer, Runnable> PENDING_FILTER_BAR_SYNC =
        new IdentityHashMap<>();
    /** Отмена устаревших htmlApply poll (fix10). */
    private static final IdentityHashMap<ContentAssistant, Integer> HTML_APPLY_GEN =
        new IdentityHashMap<>();
    private static final AtomicInteger HTML_APPLY_SEQ = new AtomicInteger();
    /** Dedupe browser HTML reload (fix10b). */
    private static final IdentityHashMap<ContentAssistant, String> LAST_APPLIED_HTML_DIGEST =
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

    static void beginSmartLiteralSessionOpen()
    {
        FORCE_SMART_LITERAL_OPEN.set(Boolean.TRUE);
        RECOMPUTE_TRIGGER.set("sessionOpen"); //$NON-NLS-1$
    }

    static void beginRecomputeTrigger(String trigger)
    {
        if (trigger != null && !trigger.isEmpty())
            RECOMPUTE_TRIGGER.set(trigger);
    }

    static String peekRecomputeTrigger()
    {
        String trigger = RECOMPUTE_TRIGGER.get();
        return trigger != null ? trigger : "unknown"; //$NON-NLS-1$
    }

    private static void clearRecomputeContext()
    {
        FORCE_SMART_LITERAL_OPEN.remove();
        RECOMPUTE_TRIGGER.remove();
    }

    private static boolean isLiteralIrExpected(SourceViewer viewer,
                                               SmartContentAssistProcessor processor, int caret)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        TextEditorFacade facade = reloader != null ? reloader.getFacade() : null;
        return reloader != null && (
            IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer) != null
            || reloader.isManualIrAssistPending()
            || reloader.isWordsTableReady()
            || reloader.isWordsTableFetchInFlightForContext(caret)
            || processor.isIrOnlyManualMode());
    }

    private static void logSessionPopupSync(String phase, boolean inLiteral, boolean irExpected,
                                            int stableNAfterSeed, String filter,
                                            boolean shouldRecompute, boolean forceSmartOpen,
                                            String recomputeRoute)
    {
}

    /** H78: literal auto/debounce — fullSmart vs literalStockOnly (throttled). */
    private static void logLiteralAutoSmartRoute(boolean inLiteral, String filter, int proposalN,
                                                 String route)
    {
        if (!inLiteral || literalAutoSmartRouteLogs.incrementAndGet() > 10)
            return;
        String filterEsc = filter != null
            ? ContentAssistDebug.jsonEscapeForLog(filter) : ""; //$NON-NLS-1$
}

    private static void logSmartApplyMarker(List<ICompletionProposal> displayList, int fullCount)
    {
        ICompletionProposal first = displayList != null && !displayList.isEmpty()
            ? displayList.get(0) : null;
        boolean smart = first instanceof SmartCompletionProposal;
        String delegateClass = "null"; //$NON-NLS-1$
        String firstKey = ""; //$NON-NLS-1$
        if (first != null)
        {
            ICompletionProposal unwrapped = SmartContentAssistProcessor.unwrapProposal(first);
            delegateClass = unwrapped.getClass().getSimpleName();
            String display = unwrapped.getDisplayString();
            if (display != null)
            {
                int colon = display.indexOf(':');
                firstKey = colon > 0 ? display.substring(0, colon).trim() : display.trim();
                if (firstKey.length() > 40)
                    firstKey = firstKey.substring(0, 40);
            }
        }
}

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
                        boolean inLiteral = caret >= 0
                            && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
                        int stableNAfterSeed = -1;
                        if (inLiteral)
                        {
                            seedStableDelegateFromVisiblePopup(assistant, processor);
                            stableNAfterSeed = processor.literalStableDelegateCount();
                        }
                        if (caret >= 0)
                            SmartContentAssistProcessor.primeFilterTrackerOnly(viewer, caret);
                        String filter = SmartFilterTracker.getCurrentFilter();
                        boolean irExpected = inLiteral
                            && isLiteralIrExpected(viewer, processor, caret);
                        boolean forceSmartOpen = inLiteral && !irExpected;
                        // REGRESSION (каретка в конце Метод(); / Найти()): на member-access
                        // session sync не должен делать stock+recompute — даже при непустом
                        // префиксе (тз.Найти + Ctrl+Space). createProposals → DataEvent.clear
                        // → LinkedMode не входит. Список уже отфильтрован первичным compute;
                        // здесь только in-place collapse (+ при префиксе refresh из кэша
                        // без stock filter).
                        boolean memberAccess = isMemberAccessAtCaret(doc, caret);
                        boolean shouldRecompute = shouldRecomputePopupList(filter, false, doc, caret)
                            && !memberAccess;
                        logSessionPopupSync("beforeRecompute", inLiteral, irExpected, //$NON-NLS-1$
                            stableNAfterSeed, filter, shouldRecompute, forceSmartOpen,
                            memberAccess ? "inplaceCollapse" : null); //$NON-NLS-1$
                        if (forceSmartOpen)
                        {
                            beginSmartLiteralSessionOpen();
                            if (recomputePopupList(assistant, viewer, processor, caret))
                            {
                                logSessionPopupSync("afterRecompute", inLiteral, irExpected, //$NON-NLS-1$
                                    stableNAfterSeed, filter, true, true, "fullSmart"); //$NON-NLS-1$
                                refreshAdditionalInfo(assistant);
                                return;
                            }
                        }
                        else if (memberAccess)
                        {
                            processor.cancelDeferredDelegateComputes();
                            boolean synced = syncMemberAccessPopupInPlace(assistant, viewer,
                                processor, caret, filter);
                            logSessionPopupSync(synced ? "afterRecompute" : "skipped", //$NON-NLS-1$ //$NON-NLS-2$
                                inLiteral, irExpected, stableNAfterSeed, filter, false, false,
                                synced ? "inplaceCollapse" : "inplaceCollapseMiss"); //$NON-NLS-1$ //$NON-NLS-2$
                            if (synced)
                                refreshAdditionalInfo(assistant);
                            return;
                        }
                        else if (shouldRecompute)
                        {
                            beginRecomputeTrigger(inLiteral ? "sessionOpen" : "debounce"); //$NON-NLS-1$ //$NON-NLS-2$
                            runStockFilterRunnable(assistant);
                            if (recomputePopupList(assistant, viewer, processor, caret))
                            {
                                logSessionPopupSync("afterRecompute", inLiteral, irExpected, //$NON-NLS-1$
                                    stableNAfterSeed, filter, true, false,
                                    inLiteral && !irExpected ? "literalStockOnly" : "fullSmart"); //$NON-NLS-1$ //$NON-NLS-2$
                                refreshAdditionalInfo(assistant);
                                return;
                            }
                        }
                        else
                        {
                            logSessionPopupSync("skipped", inLiteral, irExpected, //$NON-NLS-1$
                                stableNAfterSeed, filter, false, forceSmartOpen, "skipped"); //$NON-NLS-1$
                            return;
                        }
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
        cancelFilterBarSetup(viewer);
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

    /**
     * Только нижняя панель popup (флажок + «Родитель:») — без recompute/stock filter.
     * Для literal irOnly/deferred, где полный {@link #scheduleSessionPopupSync} пропущен.
     */
    public static void scheduleFilterBarSetup(ContentAssistant assistant, SourceViewer viewer,
                                               SmartContentAssistProcessor processor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (assistant == null || viewer == null || processor == null)
            return;
        cancelFilterBarSetup(viewer);
        Control widget = viewer.getTextWidget() != null ? (Control) viewer.getTextWidget() : null;
        if (widget == null || widget.isDisposed())
            return;
        Display display = widget.getDisplay();
        if (display == null || display.isDisposed())
            return;

        final int gen = FILTER_BAR_SYNC_GEN.get();
        final int[] step = { 0 };
        Runnable task = new Runnable() {
            @Override
            public void run()
            {
                PENDING_FILTER_BAR_SYNC.remove(viewer);
                if (widget.isDisposed() || gen != FILTER_BAR_SYNC_GEN.get())
                    return;
                if (ContentAssistSessionReloader.getActiveAssistant() != assistant)
                    return;
                if (!isPopupVisible(assistant))
                {
                    step[0]++;
                    if (step[0] < SESSION_POPUP_SYNC_DELAYS_MS.length)
                    {
                        PENDING_FILTER_BAR_SYNC.put(viewer, this);
                        display.timerExec(SESSION_POPUP_SYNC_DELAYS_MS[step[0]], this);
                    }
                    return;
                }
                try
                {
                    ContentAssistPopupUi.ensureFilterToggle(assistant, viewer, processor);
                    ContentAssistPopupUi.updateContextTypeLabel(viewer);
                    boolean barCreated = ContentAssistPopupUi.isFilterBarCreated(assistant);
                    String contextLabel = ContentAssistPopupUi.peekContextTypeLabel(viewer);
                    String contextEsc = contextLabel != null
                        ? contextLabel.replace("\\", "\\\\").replace("\"", "\\\"") : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
}
                catch (Exception e)
                {
                    ContentAssistDebug.log("filterBarSetup ERROR: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        };
        PENDING_FILTER_BAR_SYNC.put(viewer, task);
        display.timerExec(SESSION_POPUP_SYNC_DELAYS_MS[0], task);
    }

    public static void cancelFilterBarSetup(SourceViewer viewer)
    {
        FILTER_BAR_SYNC_GEN.incrementAndGet();
        if (viewer == null)
            return;
        Runnable pending = PENDING_FILTER_BAR_SYNC.remove(viewer);
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
        lastSyncedIrMergeGen = -1;
        FILTER_TASK_GEN.incrementAndGet();
        LAST_APPLIED_HTML_DIGEST.clear();
        HTML_APPLY_GEN.clear();
    }

    /** Сброс short-circuit recompute (число строк то же, порядок после ИР другой). */
    static void invalidateSyncSnapshot()
    {
        lastSyncedCount = -1;
        lastSyncedDisplayCount = -1;
        lastSyncedIrMergeGen = -1;
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
        final long _t0 = System.nanoTime();
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
            boolean literalIrMerge = inLiteralRecompute
                && (processor.isIrOnlyManualMode()
                    || processor.hasIrProposalsForCurrentContext()
                    || processor.isIrWordsResolvedForContext()
                    || processor.shouldRefreshIrPopup());

            ContentAssistSessionReloader literalReloader =
                ContentAssistSessionReloader.getActiveReloader();
            TextEditorFacade literalFacade = literalReloader != null
                ? literalReloader.getFacade() : null;
            boolean literalIrExpected = literalReloader != null && (
                IrBslExpressionHtmlSupport.resolveIrSessionForAssist(literalFacade, viewer) != null
                || literalReloader.isManualIrAssistPending()
                || literalReloader.isWordsTableReady()
                || literalReloader.isWordsTableFetchInFlightForContext(caret)
                || processor.isIrOnlyManualMode());

            final boolean forceSmartLiteralOpen =
                Boolean.TRUE.equals(FORCE_SMART_LITERAL_OPEN.get());
            if (forceSmartLiteralOpen)
                FORCE_SMART_LITERAL_OPEN.remove();

            if (inLiteralRecompute && !literalIrMerge && !literalIrExpected
                && !restoreAfterFilterToggle && !forceSmartLiteralOpen
                && !SmartAssistFilterState.isSmartFilterEnabled())
            {
                logLiteralAutoSmartRoute(inLiteralRecompute, SmartFilterTracker.getCurrentFilter(),
                    -1, "literalStockOnly"); //$NON-NLS-1$
                ContentAssistSessionReloader auditReloader =
                    ContentAssistSessionReloader.getActiveReloader();
                ContentAssistPopupSync.auditLiteralPopupList(assistant, viewer, processor,
                    auditReloader != null ? auditReloader.getFacade() : null,
                    auditReloader != null ? auditReloader.getExpectedLiteralIrCount() : -1,
                    "literalStockOnly"); //$NON-NLS-1$
                int tableRowsBefore = tableItemCount(popup);
                int filteredBefore = filteredProposalCount(popup);
runStockFilterRunnable(assistant);
                finishSyncCycle(popup);
                int tableRowsAfter = tableItemCount(popup);
                int filteredAfter = filteredProposalCount(popup);
return true;
            }

            String filter = SmartFilterTracker.getCurrentFilter();
            ICompletionProposal[] proposals = processor.filterCachedProposalsForPopup(viewer, caret,
                filter);
            if (proposals == null)
            {
proposals = processor.computeForPopupRefresh(viewer, caret);
            }
            if (proposals == null)
                proposals = new ICompletionProposal[0];
            if (inLiteralRecompute && !literalIrMerge && !literalIrExpected
                && SmartAssistFilterState.isSmartFilterEnabled())
            {
                logLiteralAutoSmartRoute(inLiteralRecompute, filter, proposals.length,
                    "fullSmart"); //$NON-NLS-1$
            }
final int fullProposalCount = proposals.length;

            final boolean fastIrMergeReset =
                ContentAssistSessionReloader.shouldResetSelectionAfterIrMerge(
                    SmartContentAssistProcessor.firstProposalDedupKey(proposals));
            ContentAssistSessionReloader mergeReloader =
                ContentAssistSessionReloader.getActiveReloader();
            final boolean forceIrMergeReplace = mergeReloader != null
                && mergeReloader.isIrMergeGenerationBumpPending();
            final boolean resetToFirst = restoreAfterFilterToggle
                ? SmartAssistFilterState.isSmartFilterEnabled()
                : (shouldResetSelectionToFirst() || fastIrMergeReset);
            filter = SmartFilterTracker.getCurrentFilter();
            boolean smartEnabled = SmartAssistFilterState.isSmartFilterEnabled();
            if (!filter.equals(lastSyncedFilter))
                popupDisplayLimit = INITIAL_POPUP_DISPLAY_LIMIT;
            int displayCount = Math.min(proposals.length, popupDisplayLimit);
            int mergeGen = mergeReloader != null ? mergeReloader.getIrMergeGeneration() : -1;
            if (filter.equals(lastSyncedFilter)
                && proposals.length == lastSyncedCount
                && displayCount == lastSyncedDisplayCount
                && smartEnabled == lastSyncedSmartEnabled
                && mergeGen == lastSyncedIrMergeGen
                && !forceIrMergeReplace)
            {
if (fastIrMergeReset)
                {
                    try
                    {
                        syncProposalSelectionOnly(popup, true);
                    }
                    catch (Exception e)
                    {
                        ContentAssistDebug.log("popupSync fastIrReset ERROR: " //$NON-NLS-1$
                            + e.getMessage());
                    }
                }
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
            lastSyncedIrMergeGen = mergeGen;
            if (mergeReloader != null && forceIrMergeReplace)
                mergeReloader.consumeIrMergeGenerationBump();
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

if (inLiteralRecompute && literalIrMerge)
                ContentAssistPopupUi.ensureFilterToggle(assistant, viewer, processor);

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
            long elapsedMs = (System.nanoTime() - _t0) / 1_000_000;
if (viewer != null && isPopupVisible(assistant))
                ContentAssistPopupUi.updateContextTypeLabel(viewer);
            RECOMPUTE_GUARD.set(Boolean.FALSE);
            clearRecomputeContext();
            ContentAssistSessionReloader reloader =
                ContentAssistSessionReloader.getActiveReloader();
            if (reloader == null || !reloader.isLiteralOpenSetupPhase()
                || !reloader.isPendingIrPopupRefresh())
            {
                ContentAssistSessionReloader.flushPendingPopupRefreshIfAny();
            }
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
        List<ICompletionProposal> listToApply = displayList;
        if (processor != null && processor.isIrWordsResolvedForContext()
            && processor.resolvedIrProposalCount() > 0)
            listToApply = SmartContentAssistProcessor.stripEmptyPlaceholderList(displayList);
        setProposalsAndRestoreSelection(popup, listToApply, false, saved, resetToFirst, restoreNow);

        List<ICompletionProposal> applied = (List<ICompletionProposal>)
            fFilteredProposalsField.get(popup);
        if (fComputedProposalsField != null && applied != null)
            fComputedProposalsField.set(popup, applied);
installPopupScrollWatcher(popup, assistant, viewer, processor);

        int tableRows = tableItemCount(popup);
        logSyncResult(fullCount, listToApply.size(), tableRows,
            SmartFilterTracker.getCurrentFilter(), resetToFirst);
        logSmartApplyMarker(listToApply, fullCount);

        if (tableRows >= 0 && tableRows != listToApply.size() && !listToApply.isEmpty())
            forceTableRefresh(popup, listToApply, saved, resetToFirst, restoreNow);

        if (runStockFilterAfter)
        {
            runStockFilterRunnable(assistant);
if (processor != null && processor.isIrWordsResolvedForContext()
                && processor.resolvedIrProposalCount() > 0)
                purgeEmptyPlaceholderFromPopup(popup, listToApply, saved, resetToFirst, restoreNow);
        }

        if (deferRestoreUntilStockFilter && restoreAfterFilterToggle)
        {
            applied = (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            restoreSelection(popup, saved, applied);
        }
        boolean selectionNotifyCalled = repinLiteralSidePanelAfterListSync(
            popup, assistant, viewer, processor);
        logPostRecomputeSidePanelIfLiteralIr(popup, assistant, viewer, processor, fullCount,
            tableRows, selectionNotifyCalled);
    }

    /** После stock filter: убрать JFace EmptyProposal, если в popup есть элементы ИР. */
    private static void purgeEmptyPlaceholderFromPopup(Object popup,
                                                       List<ICompletionProposal> fallbackList,
                                                       SavedSelection saved, boolean resetToFirst,
                                                       boolean restoreNow) throws Exception
    {
        if (popup == null || fFilteredProposalsField == null)
            return;
        List<ICompletionProposal> filtered =
            (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
        if (filtered == null || filtered.isEmpty())
            return;
        boolean hasIr = false;
        boolean hasEmpty = false;
        for (ICompletionProposal p : filtered)
        {
            if (SmartContentAssistProcessor.unwrapProposal(p) instanceof IrCompletionProposal)
                hasIr = true;
            if (SmartContentAssistProcessor.isEmptyPlaceholderProposal(p))
                hasEmpty = true;
        }
        if (!hasIr || !hasEmpty)
            return;
        List<ICompletionProposal> cleaned =
            SmartContentAssistProcessor.stripEmptyPlaceholderList(filtered);
        if (cleaned.size() == filtered.size())
            return;
        setProposalsAndRestoreSelection(popup, cleaned, false, saved, resetToFirst, restoreNow);
        if (fComputedProposalsField != null)
            fComputedProposalsField.set(popup, cleaned);
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
                String currentFilter = SmartFilterTracker.getCurrentFilter();
                if (isMemberAccessAtCaret(doc, caret))
                {
                    processor.cancelDeferredDelegateComputes();
                    syncMemberAccessPopupInPlace(assistant, viewer, processor, caret,
                        currentFilter);
                }
                else if (shouldRecomputePopupList(currentFilter, false, doc, caret))
                {
                    runStockFilterRunnable(assistant);
                    if (SmartAssistFilterState.isSmartFilterEnabled())
                    {
                        beginRecomputeTrigger("debounce"); //$NON-NLS-1$
                        recomputePopupList(assistant, viewer, processor);
                    }
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

    /**
     * Сброс pending-флагов без отмены уже запланированного filter debounce
     * (no-op collapse / in-place filter на member-access).
     */
    private static void finishSyncCycleLight(Object popup)
    {
        clearPendingDocumentEvents(popup);
        clearFilterPending(popup);
    }

    // ---- logging ------------------------------------------------------------

    /**
     * JFace {@code CompletionProposalPopup} пересоздаёт свою {@code Table} асинхронно при смене
     * контекста (например, при вводе точки для member-access) — попап (внешний объект) остаётся
     * тем же, но поле с таблицей на короткое время {@code null}/disposed, пока не создастся новая.
     * Подтверждено диагностикой (issue: подсветка не подключалась после точки): из 5 попыток
     * подряд только одна попала на валидную таблицу. Короткий ограниченный повтор ловит момент
     * готовности новой таблицы вместо однократной попытки, которая почти всегда промахивается
     * мимо окна.
     */
    private static final int INSTALL_RETRY_MAX_ATTEMPTS = 20;
    private static final int INSTALL_RETRY_DELAY_MS = 50;

    private static void installPopupScrollWatcher(Object popup, ContentAssistant assistant,
                                                  SourceViewer viewer,
                                                  SmartContentAssistProcessor processor)
    {
        installPopupScrollWatcherAttempt(popup, assistant, viewer, processor, 0);
    }

    private static void installPopupScrollWatcherAttempt(Object popup, ContentAssistant assistant,
                                                  SourceViewer viewer,
                                                  SmartContentAssistProcessor processor,
                                                  int attempt)
    {
        if (popup == null || POPUP_SCROLL_LISTENERS.containsKey(popup))
            return;
        try
        {
            initPopupReflection(popup);
            Table table = getProposalTable(popup);
            if (table == null || table.isDisposed())
            {
                if (attempt < INSTALL_RETRY_MAX_ATTEMPTS)
                {
                    Display display = Display.getDefault();
                    if (display != null && !display.isDisposed())
                    {
                        display.timerExec(INSTALL_RETRY_DELAY_MS, () ->
                            installPopupScrollWatcherAttempt(popup, assistant, viewer, processor,
                                attempt + 1));
                    }
                }
                return;
            }
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
        {
            beginRecomputeTrigger("debounce"); //$NON-NLS-1$
            recomputePopupList(assistant, viewer, processor);
        }
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

    /** Число строк popup для literal-open skip recompute (fix10). */
    public static int tableItemCountForAssistant(ContentAssistant assistant)
    {
        if (assistant == null)
            return -1;
        try
        {
            Object popup = getPopup(assistant);
            return popup != null ? tableItemCount(popup) : -1;
        }
        catch (Exception ignored)
        {
            return -1;
        }
    }

    /** Размер {@code fFilteredProposals} popup (диагностика H65–H69). */
    public static int filteredProposalCountForAssistant(ContentAssistant assistant)
    {
        if (assistant == null)
            return -1;
        try
        {
            Object popup = getPopup(assistant);
            return popup != null ? filteredProposalCount(popup) : -1;
        }
        catch (Exception ignored)
        {
            return -1;
        }
    }

    private static int filteredProposalCount(Object popup)
    {
        try
        {
            if (popup == null || fFilteredProposalsField == null)
                return -1;
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            return list != null ? list.size() : -1;
        }
        catch (Exception ignored)
        {
            return -1;
        }
    }

    /** Заполнить stable/delegate cache из текущего popup (literal без ИР, fix11). */
    static void seedStableDelegateFromVisiblePopup(ContentAssistant assistant,
                                                   SmartContentAssistProcessor processor)
    {
        if (assistant == null || processor == null || !isPopupVisible(assistant))
            return;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            initPopupReflection(popup);
            if (fFilteredProposalsField == null)
                return;
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null || list.isEmpty())
                return;
            processor.absorbStableFromPopupProposals(
                list.toArray(new ICompletionProposal[list.size()]));
        }
        catch (Exception ignored) {}
    }

    static void logSidePanelRefresh(String op)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
}

    static void logBrowserContentLoad(String op, boolean skippedDuplicate)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
        long ms = reloader != null ? reloader.msSinceLiteralOpen() : -1;
}

    static void logLiteralBrowserPhase(String phase, boolean creatorPatched, boolean hasBrowser,
        boolean pinOk, boolean htmlApplyScheduled)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        String controlClass = assistant != null
            ? jsonEscapeControlClass(describeSideInformationControlClass(assistant)) : "null"; //$NON-NLS-1$
}

    public static void logLiteralSideHintSuppressed(boolean fromSelectedActivation, String reason)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
        long ms = reloader != null ? reloader.msSinceLiteralOpen() : -1;
}

    private static void logHtmlApplyPollAttempt(int attempt, boolean applied, boolean skippedDuplicate)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
        long ms = reloader != null ? reloader.msSinceLiteralOpen() : -1;
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        IInformationControl anyControl = assistant != null
            ? resolveAnyInformationControl(assistant) : null;
        boolean hasBrowser = anyControl != null
            && IrBslHoverHtml.findControlBrowser(anyControl) != null;
        boolean browserApply = applied && hasBrowser;
        boolean textControlApply = applied && !hasBrowser;
}

    /** H39: состояние side panel сразу после merge-recompute. */
    private static void logPostRecomputeSidePanelIfLiteralIr(Object popup,
        ContentAssistant assistant, SourceViewer viewer,
        SmartContentAssistProcessor processor, int proposalCount, int tableRows,
        boolean selectionNotifyCalled)
    {
        if (assistant == null || viewer == null || processor == null)
            return;
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
        if (!SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret))
            return;
        if (!processor.hasIrProposalsForCurrentContext()
            && !processor.shouldRefreshIrPopup()
            && !processor.isIrOnlyManualMode())
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
        long ms = reloader != null ? reloader.msSinceLiteralOpen() : -1L;
        int selectedIndex = -1;
        String selectedDisplayKey = ""; //$NON-NLS-1$
        String delegateClass = "null"; //$NON-NLS-1$
        boolean isIr = false;
        boolean isOverlap = false;
        try
        {
            Table table = getProposalTable(popup);
            if (table != null && !table.isDisposed())
                selectedIndex = table.getSelectionIndex();
            ICompletionProposal selected = resolveProposalByDisplay(popup, null);
            if (selected != null)
            {
                selectedDisplayKey = selected.getDisplayString() != null
                    ? selected.getDisplayString() : ""; //$NON-NLS-1$
                ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(selected);
                if (raw != null)
                {
                    delegateClass = raw.getClass().getSimpleName();
                    isIr = raw instanceof IrCompletionProposal;
                }
                if (selected instanceof SmartCompletionProposal smart)
                    isOverlap = smart.usesIrAssistBrowserForSideHint() && !isIr;
            }
        }
        catch (Exception ignored) {}
        String controlClass = describeSideInformationControlClass(assistant);
        boolean hasBrowser = hasAssistBrowserControl(assistant);
        boolean sideShellVisible = false;
        int sideShellWidth = -1;
        int[] bounds = resolveSideShellBounds(assistant);
        if (bounds != null)
        {
            sideShellWidth = bounds[2];
            sideShellVisible = bounds[2] > 0;
        }
        IInformationControl control = resolveAnyInformationControl(assistant);
        if (control instanceof IInformationControlExtension5 ext5)
            sideShellVisible = ext5.isVisible();
        boolean pendingRefresh = reloader != null && reloader.isPendingPopupRefresh();
        int irMergeGen = reloader != null ? reloader.getIrMergeGeneration() : -1;
}

    /**
     * Repin боковой подсказки после merge-recompute (fix10i / H39).
     *
     * @return {@code true} если вызван {@link #notifyAdditionalInfoSelectionChanged}
     */
    private static boolean repinLiteralSidePanelAfterListSync(Object popup,
        ContentAssistant assistant, SourceViewer viewer,
        SmartContentAssistProcessor processor)
    {
        if (assistant == null || viewer == null || processor == null)
            return false;
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
        if (!SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret))
            return false;
        if (!processor.hasIrProposalsForCurrentContext()
            && !processor.shouldRefreshIrPopup()
            && !processor.isIrOnlyManualMode())
            return false;
        if (!hasAssistBrowserControl(assistant))
        {
            boolean ready = forceLiteralSidePanelBrowserReady(assistant, viewer);
            if (!ready && !hasAssistBrowserControl(assistant))
            {
                logCreatorGate("repin", false, false, "blocked"); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
        notifyAdditionalInfoSelectionChanged(assistant);
        pinIrSideHintForCurrentSelection(assistant, viewer);
        return true;
    }

    private static void logCreatorGate(String path, boolean hasBrowser,
        boolean creatorOk, String decision)
    {
}

    /** H42: геометрия shell боковой подсказки после configure/recreate. */
    static void logSidePanelGeometry(String op)
    {
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
        String controlClass = assistant != null
            ? describeSideInformationControlClass(assistant) : null;
        boolean hasBrowser = assistant != null && hasAssistBrowserControl(assistant);
        int[] bounds = assistant != null ? resolveSideShellBounds(assistant) : null;
        int x = bounds != null ? bounds[0] : -1;
        int y = bounds != null ? bounds[1] : -1;
        int w = bounds != null ? bounds[2] : -1;
        int h = bounds != null ? bounds[3] : -1;
        boolean visible = false;
        if (assistant != null)
        {
            IInformationControl control = resolveAnyInformationControl(assistant);
            if (control instanceof IInformationControlExtension5 ext5)
                visible = ext5.isVisible();
        }
}

    private static int[] resolveSideShellBounds(ContentAssistant assistant)
    {
        if (assistant == null)
            return null;
        IInformationControl control = resolveAnyInformationControl(assistant);
        if (control == null)
            return null;
        try
        {
            Control widget = IrBslHoverHtml.findControlBrowser(control);
            if (widget == null)
            {
                Object shellObj = Global.invoke(control, "getShell"); //$NON-NLS-1$
                if (shellObj instanceof Control c)
                    widget = c;
            }
            if (widget != null && !widget.isDisposed())
            {
                org.eclipse.swt.widgets.Shell shell = widget.getShell();
                if (shell != null && !shell.isDisposed())
                {
                    org.eclipse.swt.graphics.Rectangle r = shell.getBounds();
                    return new int[] {r.x, r.y, r.width, r.height};
                }
            }
        }
        catch (Exception ignored) {}
        return null;
    }

    private static String htmlDigest(String html)
    {
        if (html == null || html.isEmpty())
            return ""; //$NON-NLS-1$
        return html.length() + ":" + html.hashCode(); //$NON-NLS-1$
    }

    /** Первая строка popup — {@link IrCompletionProposal} (fix10b listReady). */
    public static boolean firstProposalIsIr(ContentAssistant assistant)
    {
        if (assistant == null)
            return false;
        try
        {
            Object popup = getPopupObject(assistant);
            if (popup == null || fFilteredProposalsField == null)
                return false;
            initPopupReflection(popup);
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null || list.isEmpty())
                return false;
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(list.get(0));
            return raw instanceof IrCompletionProposal;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /** H37: результат аудита наполнения literal popup (ИР подключён). */
    public static final class LiteralPopupListAudit
    {
        public boolean ok;
        public String phase;
        public int expectedIrN;
        public int tableRows;
        public int popupIr;
        public int popupEdt;
        public int proposalCount;
        public final List<String> reasons = new ArrayList<>();
    }

    /**
     * Аудит списка assist в строковом литерале при подключённом ИР (fix10g-diag).
     *
     * @return {@code null} если не literal или ИР не подключён
     */
    public static LiteralPopupListAudit auditLiteralPopupList(ContentAssistant assistant,
        SourceViewer viewer, SmartContentAssistProcessor processor, BslXtextEditor editor,
        int expectedIrN, String phase)
    {
        return auditLiteralPopupList(assistant, viewer, processor,
            editor != null ? new BslTextEditorFacade(editor) : null, expectedIrN, phase);
    }

    public static LiteralPopupListAudit auditLiteralPopupList(ContentAssistant assistant,
        SourceViewer viewer, SmartContentAssistProcessor processor, TextEditorFacade facade,
        int expectedIrN, String phase)
    {
        if (processor == null || viewer == null || phase == null)
            return null;
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
        if (caret < 0 || !SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret))
            return null;
        if (IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer) == null)
            return null;
        if (expectedIrN < 0 && processor.isIrWordsResolvedForContext())
            expectedIrN = processor.resolvedIrProposalCount();
        LiteralPopupListAudit audit = new LiteralPopupListAudit();
        audit.phase = phase;
        audit.expectedIrN = expectedIrN;
        PopupCreatorScan scan = scanPopupCreators(assistant);
        audit.popupIr = scan.ir;
        audit.popupEdt = scan.edt;
        audit.proposalCount = scan.total;
        audit.tableRows = tableItemCountForAssistant(assistant);
        boolean irOnly = processor.isIrOnlyManualMode();
        boolean irResolved = processor.isIrWordsResolvedForContext();
        boolean firstIr = firstProposalIsIr(assistant);
        boolean countPhase = isLiteralListCountAuditPhase(phase);
        if (irOnly && scan.edt > 0)
            audit.reasons.add("stockInIrOnlyList"); //$NON-NLS-1$
        if (countPhase && expectedIrN > 0 && audit.tableRows != expectedIrN)
        {
            boolean mergedLiteralPopup = "recomputeApplied".equals(phase) //$NON-NLS-1$
                && audit.proposalCount > expectedIrN;
            if (!mergedLiteralPopup)
                audit.reasons.add("rowCountMismatch"); //$NON-NLS-1$
        }
        if (expectedIrN > 1 && audit.tableRows == 1)
            audit.reasons.add("singleRowMultiIr"); //$NON-NLS-1$
        if (expectedIrN > 0 && audit.tableRows > 0 && !firstIr && (irOnly || irResolved))
        {
            boolean overlapMergeDesign = !irOnly && irResolved && audit.proposalCount > expectedIrN
                && audit.popupIr < expectedIrN;
            if (!overlapMergeDesign)
                audit.reasons.add("firstRowNotIr"); //$NON-NLS-1$
        }
        if (expectedIrN > 0 && irResolved && audit.tableRows == 0)
            audit.reasons.add("emptyWithSnapshot"); //$NON-NLS-1$
        if ("literalStockOnly".equals(phase) //$NON-NLS-1$
            && (irOnly || processor.hasIrProposalsForCurrentContext()))
            audit.reasons.add("unexpectedStockRecompute"); //$NON-NLS-1$
        if ("recoveryAfter".equals(phase) && !audit.reasons.isEmpty()) //$NON-NLS-1$
            audit.reasons.add("recoveryStillBad"); //$NON-NLS-1$
        audit.ok = audit.reasons.isEmpty();
        emitLiteralListAudit(audit, processor, irOnly, irResolved);
        return audit;
    }

    /**
     * H37: рассинхрон compute vs snapshot ИР (literal + ИР подключён).
     */
    public static void auditLiteralComputeReturn(SourceViewer viewer, BslXtextEditor editor,
        SmartContentAssistProcessor processor, int returnedN, String computePhase,
        String extraReason)
    {
        if (processor == null || viewer == null || computePhase == null)
            return;
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
        if (caret < 0 || !SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret))
            return;
        if (IrBslExpressionHtmlSupport.resolveIrSessionForAssist(editor, viewer) == null)
            return;
        LiteralPopupListAudit audit = new LiteralPopupListAudit();
        audit.phase = computePhase;
        audit.expectedIrN = processor.resolvedIrProposalCount();
        audit.proposalCount = returnedN;
        audit.tableRows = -1;
        String filter = SmartFilterTracker.getCurrentFilter();
        boolean filterEmpty = filter == null || filter.isEmpty();
        if (processor.isIrWordsResolvedForContext() && filterEmpty
            && audit.expectedIrN > 0 && returnedN < audit.expectedIrN)
            audit.reasons.add("computeBelowSnapshot"); //$NON-NLS-1$
        if (extraReason != null && !extraReason.isEmpty())
            audit.reasons.add(extraReason);
        audit.ok = audit.reasons.isEmpty();
        emitLiteralListAudit(audit, processor, processor.isIrOnlyManualMode(),
            processor.isIrWordsResolvedForContext());
    }

    private static boolean isLiteralListCountAuditPhase(String phase)
    {
        return "afterList".equals(phase) //$NON-NLS-1$
            || "finishDone".equals(phase) //$NON-NLS-1$
            || "recoveryAfter".equals(phase) //$NON-NLS-1$
            || "recomputeApplied".equals(phase); //$NON-NLS-1$
    }

    private static void emitLiteralListAudit(LiteralPopupListAudit audit,
        SmartContentAssistProcessor processor, boolean irOnly, boolean irResolved)
    {
        if (audit.ok && !"afterList".equals(audit.phase) //$NON-NLS-1$
            && !"finishDone".equals(audit.phase)) //$NON-NLS-1$
            return;
        String filter = SmartFilterTracker.getCurrentFilter();
        String filterEsc = filter != null
            ? filter.replace("\\", "\\\\").replace("\"", "\\\"") : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
}

    private static String reasonsJson(List<String> reasons)
    {
        if (reasons == null || reasons.isEmpty())
            return "[]"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("["); //$NON-NLS-1$
        for (int i = 0; i < reasons.size(); i++)
        {
            if (i > 0)
                sb.append(',');
            sb.append('"').append(ContentAssistDebug.jsonEscapeForLog(reasons.get(i)))
                .append('"');
        }
        sb.append(']');
        return sb.toString();
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
        logSidePanelRefresh("refreshAdditionalInfo"); //$NON-NLS-1$
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
        return pinIrSideHint(assistant, ir, html, true);
    }

    /**
     * @param scheduleHtmlApply {@code false} — caller сам вызовет {@link #scheduleAssistBrowserHtmlApply}
     */
    public static boolean pinIrSideHint(
        ContentAssistant assistant, IrCompletionProposal ir, Object html,
        boolean scheduleHtmlApply)
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
            String rawHtml = IrBslHoverHtml.readHtml(html);
            String digest = htmlDigest(rawHtml);
            String prevDigest = LAST_APPLIED_HTML_DIGEST.get(assistant);
            if (digest != null && !digest.isEmpty() && digest.equals(prevDigest)
                && hasAssistBrowserControl(assistant))
            {
                logBrowserContentLoad("showInformation", true); //$NON-NLS-1$
                return true;
            }
            if (additionalInfoShowInformationMethod == null)
            {
                additionalInfoShowInformationMethod = controller.getClass().getDeclaredMethod(
                    "showInformation", ICompletionProposal.class, Object.class); //$NON-NLS-1$
                additionalInfoShowInformationMethod.setAccessible(true);
            }
            logSidePanelRefresh("showInformation"); //$NON-NLS-1$
            additionalInfoShowInformationMethod.invoke(controller, proposal,
                IrBslHoverHtml.toAssistSideHintPayload(html));
            if (digest != null && !digest.isEmpty())
            {
                LAST_APPLIED_HTML_DIGEST.put(assistant, digest);
                logBrowserContentLoad("showInformation", false); //$NON-NLS-1$
            }
            if (scheduleHtmlApply)
                scheduleAssistBrowserHtmlApply(assistant, html);
            return true;
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("pinIrSideHint: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /** ИР-only / deferred open: боковая подсказка через browser, не plain-text HTML. */
    public static void pinIrSideHintForCurrentSelection(ContentAssistant assistant)
    {
        pinIrSideHintForCurrentSelection(assistant, ContentAssistSessionReloader.getActiveViewer());
    }

    /**
     * Literal finish-path: pin после {@link #migrateLiteralSidePanelToBrowser}.
     *
     * @return {@code true} если {@code showInformation} вызван на browser control
     */
    public static boolean pinIrSideHintForCurrentSelection(ContentAssistant assistant,
        SourceViewer viewer)
    {
        if (assistant == null)
            return false;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (viewer == null)
            viewer = ContentAssistSessionReloader.getActiveViewer();
        try
        {
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return false;
            initPopupReflection(popup);
            ICompletionProposal proposal = resolveProposalByDisplay(popup, null);
            if (proposal == null)
                return false;
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
            if (raw instanceof IrCompletionProposal ir)
                return pinIrRowSideHint(assistant, viewer, reloader, ir);
            if (proposal instanceof SmartCompletionProposal sc
                && sc.usesIrAssistBrowserForSideHint())
                return pinOverlapEdtSideHint(assistant, viewer, reloader, proposal, raw);
            return false;
        }
        catch (Throwable t)
        {
            ContentAssistDebug.debugModeLog("H26", "pinIrSideHintForCurrentSelection", "error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"msg\":\"" + ContentAssistDebug.jsonEscapeForLog(t.getMessage()) //$NON-NLS-1$
                    + "\",\"className\":\"" + ContentAssistDebug.jsonEscapeForLog(t.getClass().getName()) //$NON-NLS-1$
                    + "\",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            ContentAssistDebug.log("pinIrSideHintForCurrentSelection: " + t.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static boolean pinIrRowSideHint(ContentAssistant assistant, SourceViewer viewer,
        ContentAssistSessionReloader reloader, IrCompletionProposal ir)
    {
        String cacheKey = ir.getStableCacheKey();
        String html = reloader != null ? reloader.getIrMergedHtml(cacheKey) : null;
        if (html == null || html.isEmpty())
            html = ir.getAdditionalProposalInfo();
        if (html == null || html.isEmpty())
        {
            logLiteralBrowserPhase("pinDone", false, hasAssistBrowserControl(assistant), //$NON-NLS-1$
                false, false);
            return false;
        }
        boolean hasBrowser = hasAssistBrowserControl(assistant);
        boolean pinOk = false;
        if (!hasBrowser)
        {
            ensureAssistBrowserCreatorOnController(assistant, viewer, true);
            if (!hasAssistBrowserControl(assistant))
                recreateAssistBrowserSidePanelIfNeeded(assistant, viewer);
            hasBrowser = hasAssistBrowserControl(assistant);
        }
        if (hasBrowser)
        {
            pinOk = pinIrSideHint(assistant, ir, html, false);
            if (pinOk && reloader != null)
                reloader.markIrSideHintPublished(cacheKey);
        }
        logLiteralBrowserPhase("pinDone", true, hasAssistBrowserControl(assistant), //$NON-NLS-1$
            pinOk, false);
        return pinOk;
    }

    private static boolean pinOverlapEdtSideHint(ContentAssistant assistant, SourceViewer viewer,
        ContentAssistSessionReloader reloader, ICompletionProposal proposal,
        ICompletionProposal raw)
    {
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(raw);
        String displayKey = raw.getDisplayString();
        String html = reloader != null && cacheKey != null
            ? reloader.getIrMergedHtml(cacheKey) : null;
        if ((html == null || html.isEmpty()) && reloader != null)
            html = reloader.getIrMergedHtml(displayKey);
        if (html == null || html.isEmpty())
        {
            logOverlapSideHint(raw, false, false, false);
            return false;
        }
        if (!hasAssistBrowserControl(assistant))
        {
            migrateLiteralSidePanelToBrowser(assistant, viewer);
            if (!hasAssistBrowserControl(assistant))
                ensureAssistBrowserCreatorOnController(assistant, viewer);
        }
        if (!hasAssistBrowserControl(assistant))
        {
            logCreatorGate("overlap", false, false, "blocked"); //$NON-NLS-1$ //$NON-NLS-2$
            logOverlapSideHint(raw, true, false, false);
            return false;
        }
        logCreatorGate("overlap", true, true, "allowed"); //$NON-NLS-1$ //$NON-NLS-2$
        pinMergedAdditionalInfo(assistant, displayKey, html, true);
        return true;
    }

    private static void logOverlapSideHint(ICompletionProposal raw, boolean hasMergedHtml,
        boolean hasBrowser, boolean pinInvoked)
    {
        String delegateClass = raw != null ? raw.getClass().getSimpleName() : "null"; //$NON-NLS-1$
        String controlClass = describeSideInformationControlClass(
            ContentAssistSessionReloader.getActiveAssistant());
}

    /**
     * Один fallback через 80 ms: migrate + pin без htmlApply poll (fix10c).
     */
    public static void scheduleLiteralPinFallback(ContentAssistant assistant, SourceViewer viewer)
    {
        if (assistant == null)
            return;
        if (viewer == null)
            viewer = ContentAssistSessionReloader.getActiveViewer();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        final SourceViewer viewerRef = viewer;
        display.timerExec(80, () -> {
            if (display.isDisposed() || assistant == null || !isPopupVisible(assistant))
                return;
            if (hasAssistBrowserControl(assistant))
                return;
            migrateLiteralSidePanelToBrowser(assistant, viewerRef);
            ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
            if (reloader != null)
                reloader.setLiteralBrowserPrimed(hasAssistBrowserControl(assistant));
            pinIrSideHintForCurrentSelection(assistant, viewerRef);
        });
    }

    /**
     * Активация первой строки literal/deferred popup — {@code selected()} до pin side hint.
     *
     * @return {@code true} если вызван {@link ICompletionProposalExtension2#selected}
     */
    public static boolean ensureInitialIrRowActivation(ContentAssistant assistant,
        SourceViewer viewer)
    {
        if (assistant == null || viewer == null)
            return false;
        boolean selectedCalled = false;
        String cacheKey = null;
        int htmlLenAfter = 0;
        try
        {
            ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
            if (reloader == null || !reloader.isLiteralFinishPinPending())
                notifyAdditionalInfoSelectionChanged(assistant);
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return false;
            initPopupReflection(popup);
            ICompletionProposal proposal = resolveProposalByDisplay(popup, null);
            if (proposal == null)
                return false;
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
            if (raw instanceof IrCompletionProposal ir)
                cacheKey = ir.getStableCacheKey();
            if (proposal instanceof ICompletionProposalExtension2 ext2)
            {
                ext2.selected(viewer, false);
                selectedCalled = true;
            }
            if (reloader != null && cacheKey != null)
            {
                String html = reloader.getIrMergedHtml(cacheKey);
                if ((html == null || html.isEmpty()) && raw instanceof IrCompletionProposal ir2)
                    html = ir2.getAdditionalProposalInfo();
                htmlLenAfter = html != null ? html.length() : 0;
            }
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("ensureInitialIrRowActivation: " + e.getMessage()); //$NON-NLS-1$
        }
return selectedCalled;
    }

    /**
     * Фаза A literal-open: только patch creator, без configureAndMakeVisible (fix10c).
     *
     * @return {@code true} если creator найден и применён на controller
     */
    public static boolean patchAssistBrowserCreatorOnly(ContentAssistant assistant,
        SourceViewer viewer)
    {
        boolean creatorPatched = applyBrowserCreatorPatch(assistant, viewer);
        logLiteralBrowserPhase("patchBeforeRecompute", creatorPatched, //$NON-NLS-1$
            hasAssistBrowserControl(assistant), false, false);
        return creatorPatched;
    }

    /**
     * Сброс plain side control перед первым show literal-open (fix10i).
     * Только если browser ещё не поднят.
     */
    public static void disposeStalePlainSidePanelIfNeeded(ContentAssistant assistant)
    {
        if (assistant == null || hasAssistBrowserControl(assistant))
            return;
        Object controller = resolveAdditionalInfoController(assistant);
        if (controller == null)
            return;
        try
        {
            Global.invoke(controller, "disposeInformationControl"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // первый show создаст control из пропатченного creator
        }
    }

    /**
     * Finish-path literal-open: browser до pin, без defer setup phase (fix10i).
     *
     * @return {@code true} если browser control присутствует
     */
    public static boolean forceLiteralSidePanelBrowserReady(ContentAssistant assistant,
        SourceViewer viewer)
    {
        if (assistant == null)
            return false;
        applyBrowserCreatorPatch(assistant, viewer);
        ensureAssistBrowserCreatorOnController(assistant, viewer, true);
        boolean hasBrowser = hasAssistBrowserControl(assistant);
        if (!hasBrowser)
            hasBrowser = recreateAssistBrowserSidePanelIfNeeded(assistant, viewer);
        logLiteralBrowserPhase("forceBrowserReady", true, hasBrowser, false, false); //$NON-NLS-1$
        return hasBrowser;
    }

    /**
     * Синхронизация выбора строки без {@code ext2.selected()} — не открывать plain HTML снова.
     */
    public static void syncLiteralRowSelectionWithoutPlainSelected(
        ContentAssistant assistant, SourceViewer viewer)
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
            int idx = 0;
            if (table != null && !table.isDisposed())
            {
                idx = table.getSelectionIndex();
                if (idx < 0)
                    idx = 0;
            }
            selectProposalAtIndex(popup, idx);
            notifyAdditionalInfoSelectionChanged(assistant);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("syncLiteralRowSelectionWithoutPlainSelected: " //$NON-NLS-1$
                + e.getMessage());
        }
    }

    /**
     * Фаза B literal-open: plain → browser перед pin (fix10c).
     *
     * @return {@code true} если browser control присутствует после migrate
     */
    public static boolean migrateLiteralSidePanelToBrowser(ContentAssistant assistant,
        SourceViewer viewer)
    {
        if (assistant == null)
            return false;
        boolean creatorPatched = applyBrowserCreatorPatch(assistant, viewer);
        boolean hasBrowser = hasAssistBrowserControl(assistant);
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (!hasBrowser && (reloader == null || !reloader.isLiteralOpenSetupPhase()))
        {
            ensureAssistBrowserCreatorOnController(assistant, viewer);
            hasBrowser = hasAssistBrowserControl(assistant);
        }
        logLiteralBrowserPhase("migrateBeforePin", creatorPatched, hasBrowser, false, false); //$NON-NLS-1$
        return hasBrowser;
    }

    /**
     * Визуальный plain → browser после завершения literal setup (fix10f).
     */
    public static void finishLiteralBrowserVisualRefresh(ContentAssistant assistant,
        SourceViewer viewer)
    {
        if (assistant == null)
            return;
        if (!hasAssistBrowserControl(assistant))
        {
            applyBrowserCreatorPatch(assistant, viewer);
            recreateAssistBrowserSidePanelIfNeeded(assistant, viewer);
        }
        if (hasAssistBrowserControl(assistant))
            pinIrSideHintForCurrentSelection(assistant, viewer);
    }

    private static void logLiteralCreatorResolve(String winner, boolean cold, boolean creatorOk)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
        long ms = reloader != null ? reloader.msSinceLiteralOpen() : -1;
}

    private static boolean applyBrowserCreatorPatch(ContentAssistant assistant,
        SourceViewer viewer)
    {
        if (assistant == null)
            return false;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        IInformationControlCreator creator = null;
        String winner = null;
        boolean cold = false;
        try
        {
            BslCompletionSideHintResolver.AssistBrowserCreatorTrace trace =
                BslCompletionSideHintResolver.traceAssistBrowserCreatorResolution(
                    assistant, viewer, reloader);
            creator = trace.creator;
            winner = trace.winner;
        }
        catch (Exception e)
        {
            creator = BslCompletionSideHintResolver.resolveAssistBrowserCreatorChain(
                assistant, viewer, reloader);
            if (creator != null)
                winner = "fallback"; //$NON-NLS-1$
        }
        if (creator == null && reloader != null)
        {
            creator = reloader.getAssistBrowserCreator();
            if (creator != null)
                winner = "cachedReloader"; //$NON-NLS-1$
        }
        if (creator == null && reloader != null)
        {
            creator = reloader.resolveAssistBrowserCreatorForLiteral();
            if (creator != null)
            {
                winner = "coldLiteral"; //$NON-NLS-1$
                cold = true;
            }
        }
        logLiteralCreatorResolve(winner, cold, creator != null);
        if (creator != null && reloader != null)
            reloader.rememberAssistBrowserCreator(creator);
        if (creator == null)
            return false;
        return patchCreatorOnAdditionalInfoController(
            resolveAdditionalInfoController(assistant), creator);
    }

    /**
     * Browser side panel до первого {@code showInformation(String)} — literal irOnly без session sync.
     *
     * @return {@code true} если browser creator найден и закэширован
     */
    public static boolean prepareAssistBrowserSidePanel(ContentAssistant assistant,
        SourceViewer viewer)
    {
        return ensureAssistBrowserCreatorOnController(assistant, viewer);
    }

    /**
     * Патч browser creator на {@code AdditionalInfoController} до первого showInformation.
     *
     * @return {@code true} если creator найден и применён
     */
    public static boolean ensureAssistBrowserCreatorOnController(ContentAssistant assistant,
        SourceViewer viewer)
    {
        return ensureAssistBrowserCreatorOnController(assistant, viewer, false);
    }

    /**
     * @param forceVisualRefresh {@code true} — configure/recreate даже в literal setup phase (fix10i)
     */
    public static boolean ensureAssistBrowserCreatorOnController(ContentAssistant assistant,
        SourceViewer viewer, boolean forceVisualRefresh)
    {
        if (assistant == null)
            return false;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        BslCompletionSideHintResolver.AssistBrowserCreatorTrace trace =
            new BslCompletionSideHintResolver.AssistBrowserCreatorTrace();
        trace.viewerHash = viewer != null ? System.identityHashCode(viewer) : -1;
        trace.reloaderHash = reloader != null ? System.identityHashCode(reloader) : -1;
        String controlBefore = null;
IInformationControlCreator creator = trace.creator;
        boolean creatorResolved = creator != null;
        if (!creatorResolved && reloader != null)
        {
            creator = reloader.getAssistBrowserCreator();
            if (creator != null)
            {
                creatorResolved = true;
                trace.winner = "cachedReloader"; //$NON-NLS-1$
            }
        }
        if (!creatorResolved && reloader != null)
        {
            creator = reloader.resolveAssistBrowserCreatorForLiteral();
            if (creator != null)
            {
                creatorResolved = true;
                trace.winner = "coldLiteral"; //$NON-NLS-1$
                logLiteralCreatorResolve(trace.winner, true, true);
            }
        }
        if (creator != null && reloader != null)
            reloader.rememberAssistBrowserCreator(creator);
        boolean creatorPatched = false;
        if (creator != null)
            creatorPatched = patchCreatorOnAdditionalInfoController(
                resolveAdditionalInfoController(assistant), creator);
        boolean hasBrowserAfterRefresh = hasAssistBrowserControl(assistant);
        boolean deferVisualRefresh = !forceVisualRefresh && reloader != null
            && reloader.isLiteralOpenSetupPhase();
        if (creatorPatched && !hasBrowserAfterRefresh && !deferVisualRefresh)
        {
            try
            {
                Object popup = getPopup(assistant);
                if (popup != null)
                {
                    initPopupReflection(popup);
                    if (configureAndMakeVisibleMethod != null && fProposalShellField != null)
                    {
                        Object shell = fProposalShellField.get(popup);
                        if (shell instanceof org.eclipse.swt.widgets.Shell s
                            && !s.isDisposed() && s.isVisible())
                        {
                            logSidePanelRefresh("configureAndMakeVisible"); //$NON-NLS-1$
                            configureAndMakeVisibleMethod.invoke(popup);
                            logSidePanelGeometry("configureAndMakeVisible"); //$NON-NLS-1$
                        }
                        else
                            refreshAdditionalInfo(assistant);
                    }
                    else
                        refreshAdditionalInfo(assistant);
                }
            }
            catch (Exception e)
            {
                ContentAssistDebug.log("ensureAssistBrowserCreatorOnController refresh: " //$NON-NLS-1$
                    + e.getMessage());
            }
            hasBrowserAfterRefresh = hasAssistBrowserControl(assistant);
            if (creatorPatched && !hasBrowserAfterRefresh)
                hasBrowserAfterRefresh = recreateAssistBrowserSidePanelIfNeeded(assistant, viewer);
        }
return creatorResolved && creatorPatched;
    }

    /** H20/H22: FQN side control или {@code null}. */
    public static String describeSideInformationControlClass(ContentAssistant assistant)
    {
        IInformationControl control = resolveAnyInformationControl(assistant);
        return control != null ? control.getClass().getName() : null;
    }

    /** Для H25 из {@link SmartCompletionProposal}. */
    public static boolean hasAssistBrowserSidePanel(ContentAssistant assistant)
    {
        return hasAssistBrowserControl(assistant);
    }

    private static String jsonEscapeControlClass(String className)
    {
        if (className == null)
            return "null"; //$NON-NLS-1$
        return className.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static boolean patchCreatorOnAdditionalInfoController(
        Object controller, IInformationControlCreator creator)
    {
        if (controller == null || creator == null)
            return false;
        try
        {
            Class<?> type = controller.getClass();
            while (type != null)
            {
                try
                {
                    Method m = type.getDeclaredMethod(
                        "setCustomInformationControlCreator", IInformationControlCreator.class); //$NON-NLS-1$
                    m.setAccessible(true);
                    m.invoke(controller, creator);
                    return true;
                }
                catch (NoSuchMethodException ignored)
                {
                    type = type.getSuperclass();
                }
            }
        }
        catch (Exception ignored) {}
        try
        {
            Global.setField(controller, "fInformationControlCreator", creator); //$NON-NLS-1$
            return true;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /**
     * Plain-text side panel уже создан — dispose и пересоздать browser control.
     *
     * @return {@code true} если browser control появился после recreate
     */
    public static boolean recreateAssistBrowserSidePanelIfNeeded(ContentAssistant assistant,
        SourceViewer viewer)
    {
        if (assistant == null || hasAssistBrowserControl(assistant))
            return hasAssistBrowserControl(assistant);
        applyBrowserCreatorPatch(assistant, viewer);
        Object controller = resolveAdditionalInfoController(assistant);
        if (controller == null)
            return false;
        try
        {
            Global.invoke(controller, "disposeInformationControl"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // refresh ниже
        }
        logSidePanelRefresh("recreateBrowser"); //$NON-NLS-1$
        try
        {
            Object popup = getPopup(assistant);
            if (popup != null)
            {
                initPopupReflection(popup);
                if (configureAndMakeVisibleMethod != null)
                {
                    logSidePanelRefresh("configureAndMakeVisible"); //$NON-NLS-1$
                    configureAndMakeVisibleMethod.invoke(popup);
                    logSidePanelGeometry("recreateBrowser"); //$NON-NLS-1$
                }
                else
                    refreshAdditionalInfo(assistant);
            }
            else
                refreshAdditionalInfo(assistant);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("recreateAssistBrowserSidePanelIfNeeded: " + e.getMessage()); //$NON-NLS-1$
        }
return hasBrowser;
    }

    private static boolean hasAssistBrowserControl(ContentAssistant assistant)
    {
        if (assistant == null)
            return false;
        if (resolveVisibleBrowserControl(assistant) != null)
            return true;
        IInformationControl control = resolveAnyInformationControl(assistant);
        return control != null && IrBslHoverHtml.findControlBrowser(control) != null;
    }

    private static IInformationControl resolveAnyInformationControl(ContentAssistant assistant)
    {
        Object controller = resolveAdditionalInfoController(assistant);
        if (controller == null)
            return null;
        Object control = Global.invoke(controller, "getCurrentInformationControl2"); //$NON-NLS-1$
        return control instanceof IInformationControl ic ? ic : null;
    }

    private static boolean tryApplyHtmlToAssistControl(ContentAssistant assistant, String displayHtml)
    {
        if (assistant == null || displayHtml == null || displayHtml.isEmpty())
            return false;
        String digest = htmlDigest(displayHtml);
        String prevDigest = LAST_APPLIED_HTML_DIGEST.get(assistant);
        if (digest != null && !digest.isEmpty() && digest.equals(prevDigest))
        {
            logBrowserContentLoad("htmlApply", true); //$NON-NLS-1$
            return true;
        }
        IXtextBrowserInformationControl browser = resolveVisibleBrowserControl(assistant);
        boolean rawFullDoc = IrBslHoverHtml.isFullHtmlDocument(displayHtml);
        boolean applied = false;
        if (browser != null)
            applied = IrBslHoverHtml.applyHtmlToControl(browser, displayHtml);
        if (!applied && !rawFullDoc)
        {
            IInformationControl control = resolveAnyInformationControl(assistant);
            applied = control != null && IrBslHoverHtml.applyHtmlToControl(control, displayHtml);
        }
        if (applied && digest != null && !digest.isEmpty())
        {
            LAST_APPLIED_HTML_DIGEST.put(assistant, digest);
            logBrowserContentLoad("htmlApply", false); //$NON-NLS-1$
        }
        return applied;
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
                // Пропускаем второй runStockFilterRunnable если sessionPopupSync (delay=0)
                // уже применил тот же фильтр: shouldRecomputePopupList вернёт false, и
                // повторный validate() по 1000+ proposals на UI-потоке бессмысленен.
                String currentFilter = SmartFilterTracker.getCurrentFilter();
                boolean memberAccess = isMemberAccessAtCaret(doc, caret);
                // Member-access: не stock+recompute (сброс DataEvent / LinkedMode).
                if (memberAccess && assistant != null && processor != null)
                {
                    processor.cancelDeferredDelegateComputes();
                    syncMemberAccessPopupInPlace(assistant, viewer, processor, caret,
                        currentFilter);
                }
                else if (assistant != null && processor != null
                    && shouldRecomputePopupList(currentFilter, false, doc, caret))
                {
                    runStockFilterRunnable(assistant);
                    if (SmartAssistFilterState.isSmartFilterEnabled())
                    {
                        beginRecomputeTrigger("debounce"); //$NON-NLS-1$
                        recomputePopupList(assistant, viewer, processor);
                    }
                }
                else if (!SmartAssistFilterState.isSmartFilterEnabled())
                {
                    // Smart-фильтр выключен — stock runnable нужен для синхронизации Table.
                    runStockFilterRunnable(assistant);
                }
                // else: smart-filter включён и recompute не нужен —
                //       sessionPopupSync (delay=0) уже обновил popup, второй прогон пропускаем.
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
     * Нужен ли полный {@link #recomputePopupList} (не-member пути).
     *
     * <p>Member-access ({@code obj.} / {@code obj.Преф}) обрабатывается отдельно —
     * {@link #syncMemberAccessPopupInPlace}; stock+recompute там сбрасывает DataEvent.
     *
     * @param doc   не используется (сигнатура для call-site)
     * @param caret не используется (см. {@code doc})
     */
    private static boolean shouldRecomputePopupList(String filter, boolean afterFilterToggle,
                                                    IDocument doc, int caret)
    {
        return afterFilterToggle
            || (filter != null && !filter.isEmpty());
    }

    /** Member-access ({@code obj.}) в позиции каретки. */
    private static boolean isMemberAccessAtCaret(IDocument doc, int caret)
    {
        return doc != null && caret >= 0
            && SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(doc, caret) >= 0;
    }

    /**
     * Session/debounce sync на member-access без stock filter и без
     * {@link #recomputePopupList} (они сбрасывают {@code BslDocumentListener.DataEvent}).
     *
     * <p>При непустом префиксе — фильтр из
     * {@link SmartContentAssistProcessor#filterCachedProposalsForPopup} или, при холодном
     * кэше, из {@code fComputedProposals} / видимого списка
     * ({@link SmartContentAssistProcessor#filterProposalsInPlace}). Пустой префикс —
     * {@link #collapseOptionalOverlapsInVisiblePopup}.
     */
    static boolean syncMemberAccessPopupInPlace(ContentAssistant assistant, SourceViewer viewer,
                                                SmartContentAssistProcessor processor, int caret,
                                                String filter)
    {
        if (assistant == null || !isPopupVisible(assistant))
            return false;
        try
        {
            if (processor != null && filter != null && !filter.isEmpty()
                && SmartAssistFilterState.isSmartFilterEnabled())
            {
                Object popup = getPopup(assistant);
                if (popup == null)
                    return false;
                initPopupReflection(popup);
                if (fFilteredProposalsField == null || setProposalsMethod == null)
                    return false;

                String path = "cache"; //$NON-NLS-1$
                ICompletionProposal[] filtered =
                    processor.filterCachedProposalsForPopup(viewer, caret, filter);
                List<ICompletionProposal> baseList = readMemberAccessBaseProposals(popup);
                if (filtered != null)
                {
                    // Если popup ещё не держал полную базу — взять её из тёплого кэша.
                    ICompletionProposal[] fullCached =
                        processor.filterCachedProposalsForPopup(viewer, caret, ""); //$NON-NLS-1$
                    if (fullCached != null && fullCached.length > baseList.size())
                        baseList = new ArrayList<>(Arrays.asList(fullCached));
                }
                else
                {
                    if (baseList.isEmpty())
                        return collapseOptionalOverlapsInVisiblePopup(assistant);
                    path = "visible"; //$NON-NLS-1$
                    filtered = processor.filterProposalsInPlace(
                        baseList.toArray(new ICompletionProposal[0]), filter);
                }
                if (filtered == null)
                    return collapseOptionalOverlapsInVisiblePopup(assistant);

                SavedSelection saved = saveSelection(popup);
                List<ICompletionProposal> list = new ArrayList<>(Arrays.asList(filtered));
                list = SmartContentAssistProcessor.collapseOptionalEdtOverlapsInMergedList(list);
                // Базу (полный список) не затираем отфильтрованным — иначе Backspace
                // не сможет вернуть отброшенные строки без stock recompute.
                applyMemberAccessFilteredProposals(popup, baseList, list, saved);
                List<ICompletionProposal> applied =
                    (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
finishSyncCycleLight(popup);
                return true;
            }
            return restoreMemberAccessFullListThenCollapse(assistant);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("inplaceMemberSync ERROR: " + e.getMessage()); //$NON-NLS-1$
            return collapseOptionalOverlapsInVisiblePopup(assistant);
        }
    }

    /**
     * Пустой префикс на member-access: вернуть полную базу из {@code fComputedProposals},
     * если видимый список уже был сужен in-place фильтром.
     */
    private static boolean restoreMemberAccessFullListThenCollapse(ContentAssistant assistant)
    {
        if (assistant == null || !isPopupVisible(assistant))
            return false;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return false;
            initPopupReflection(popup);
            if (fFilteredProposalsField == null || setProposalsMethod == null)
                return false;
            List<ICompletionProposal> base = readMemberAccessBaseProposals(popup);
            List<ICompletionProposal> current =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            int currentN = current != null ? current.size() : 0;
            if (!base.isEmpty() && base.size() > currentN)
            {
                SavedSelection saved = saveSelection(popup);
                List<ICompletionProposal> collapsed =
                    SmartContentAssistProcessor.collapseOptionalEdtOverlapsInMergedList(
                        new ArrayList<>(base));
                setProposalsAndRestoreSelection(popup, collapsed, false, saved, false, false);
                List<ICompletionProposal> applied =
                    (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
                if (fComputedProposalsField != null && applied != null)
                    fComputedProposalsField.set(popup, applied);
finishSyncCycleLight(popup);
                return true;
            }
            return collapseOptionalOverlapsInVisiblePopup(assistant);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("inplaceRestore ERROR: " + e.getMessage()); //$NON-NLS-1$
            return collapseOptionalOverlapsInVisiblePopup(assistant);
        }
    }

    /** Полный список member-access: предпочитаем {@code fComputedProposals}, иначе filtered. */
    private static List<ICompletionProposal> readMemberAccessBaseProposals(Object popup)
        throws Exception
    {
        List<ICompletionProposal> computed = null;
        if (fComputedProposalsField != null)
            computed = (List<ICompletionProposal>) fComputedProposalsField.get(popup);
        List<ICompletionProposal> filtered = null;
        if (fFilteredProposalsField != null)
            filtered = (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
        if (computed != null && !computed.isEmpty())
        {
            if (filtered == null || filtered.isEmpty() || computed.size() >= filtered.size())
                return new ArrayList<>(computed);
        }
        if (filtered != null && !filtered.isEmpty())
            return new ArrayList<>(filtered);
        if (computed != null)
            return new ArrayList<>(computed);
        return new ArrayList<>();
    }

    /**
     * Показывает отфильтрованный список, сохраняя {@code fComputedProposals} как полную базу.
     */
    private static void applyMemberAccessFilteredProposals(Object popup,
                                                           List<ICompletionProposal> base,
                                                           List<ICompletionProposal> filtered,
                                                           SavedSelection saved)
            throws Exception
    {
        List<ICompletionProposal> keepBase = base != null && !base.isEmpty() ? base : filtered;
        if (fComputedProposalsField != null && keepBase != null)
            fComputedProposalsField.set(popup, keepBase);
        setProposalsMethod.invoke(popup, filtered, Boolean.TRUE);
        List<ICompletionProposal> applied =
            (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
        // Не перезаписываем computed отфильтрованным результатом setProposals.
        if (fComputedProposalsField != null && keepBase != null)
            fComputedProposalsField.set(popup, keepBase);
        applySelection(popup, applied != null ? applied : filtered, saved, false, false);
    }

    /**
     * Схлопывает optional-перегрузки в уже показанном popup.
     *
     * <p>Сохраняет identity штатных EDT proposal и регистрацию
     * {@code BslDocumentListener.DataEvent} (LinkedMode → каретка внутри {@code ()}).
     * Не вызывать {@code createProposals} / {@link #runStockFilterRunnable} /
     * {@link #recomputePopupList} «рядом» на этом пути — см. javadoc класса.
     *
     * @return {@code true} если список прочитан (даже без изменений)
     */
    static boolean collapseOptionalOverlapsInVisiblePopup(ContentAssistant assistant)
    {
        if (assistant == null || !isPopupVisible(assistant))
            return false;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return false;
            initPopupReflection(popup);
            if (fFilteredProposalsField == null || setProposalsMethod == null)
                return false;
            List<ICompletionProposal> current =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (current == null || current.isEmpty())
                return false;
            SavedSelection saved = saveSelection(popup);
            List<ICompletionProposal> collapsed =
                SmartContentAssistProcessor.collapseOptionalEdtOverlapsInMergedList(
                    new ArrayList<>(current));
            if (collapsed.size() == current.size())
            {
// Не cancelDebouncedNativeFilter: иначе набор префикса после «.» теряет
                // уже запланированный filter debounce.
                finishSyncCycleLight(popup);
                return true;
            }
            setProposalsAndRestoreSelection(popup, collapsed, false, saved, false, false);
            List<ICompletionProposal> applied =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (fComputedProposalsField != null && applied != null)
                fComputedProposalsField.set(popup, applied);
finishSyncCycle(popup);
            return true;
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("inplaceCollapse ERROR: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
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
            String filterPrefix = SmartFilterTracker.getCurrentFilter();
            int tableBefore = tableItemCount(popup);
            int filteredBefore = filteredProposalCount(popup);
            boolean popupVisibleBefore = isPopupVisible(assistant);
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

    static String readFirstVisibleProposalDedupKey(ContentAssistant assistant)
    {
        if (assistant == null)
            return null;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return null;
            initPopupReflection(popup);
            if (fFilteredProposalsField == null)
                return null;
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null || list.isEmpty())
                return null;
            return SmartContentAssistProcessor.dedupKeyForMerge(list.get(0));
        }
        catch (Exception ignored)
        {
            return null;
        }
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

    /** H20: состав popup и первый EDT creator с ext3. */
    public static final class PopupCreatorScan
    {
        public int total;
        public int ir;
        public int edt;
        public int ext3WithCreator;
        public IInformationControlCreator firstCreator;
    }

    public static PopupCreatorScan scanPopupCreators(ContentAssistant assistant)
    {
        PopupCreatorScan scan = new PopupCreatorScan();
        if (assistant == null)
            return scan;
        try
        {
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return scan;
            initPopupReflection(popup);
            if (fFilteredProposalsField == null)
                return scan;
            @SuppressWarnings("unchecked")
            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            if (list == null)
                return scan;
            scan.total = list.size();
            for (ICompletionProposal proposal : list)
            {
                if (proposal == null)
                    continue;
                ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
                if (raw instanceof IrCompletionProposal)
                {
                    scan.ir++;
                    continue;
                }
                scan.edt++;
                if (raw instanceof ICompletionProposalExtension3 ext3)
                {
                    IInformationControlCreator creator = ext3.getInformationControlCreator();
                    if (creator != null)
                    {
                        scan.ext3WithCreator++;
                        if (scan.firstCreator == null)
                            scan.firstCreator = creator;
                    }
                }
            }
        }
        catch (Exception ignored) {}
        return scan;
    }

    /** Creator браузера из EDT-строк того же popup (ИР-строки его не задают). */
    public static IInformationControlCreator findAssistBrowserCreatorInPopup(
        ContentAssistant assistant)
    {
        return scanPopupCreators(assistant).firstCreator;
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
        pinMergedAdditionalInfo(assistant, displayKey, mergedInfo, false);
    }

    /**
     * @param literalOverlap {@code true} — extended htmlApply retry в литерале с overlap ИР+EDT
     */
    public static void pinMergedAdditionalInfo(
        ContentAssistant assistant, String displayKey, Object mergedInfo,
        boolean literalOverlap)
    {
        if (assistant == null || mergedInfo == null)
            return;
        try
        {
            String rawHtml = IrBslHoverHtml.readHtml(mergedInfo);
            boolean needsBrowser = rawHtml != null && !rawHtml.isEmpty()
                && IrBslHoverHtml.isFullHtmlDocument(rawHtml);
            SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
            boolean creatorOk = false;
            if (needsBrowser && !hasAssistBrowserControl(assistant))
                creatorOk = ensureAssistBrowserCreatorOnController(assistant, viewer);
            if (needsBrowser && !hasAssistBrowserControl(assistant))
            {
                logCreatorGate("pinMerged", false, creatorOk, "blocked"); //$NON-NLS-1$ //$NON-NLS-2$
                scheduleLiteralPinFallback(assistant, viewer);
                return;
            }
            if (needsBrowser)
                logCreatorGate("pinMerged", true, true, "allowed"); //$NON-NLS-1$ //$NON-NLS-2$
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
            Object payload = IrBslHoverHtml.toAssistSideHintPayload(mergedInfo);
            additionalInfoShowInformationMethod.invoke(controller, proposal, payload);
            boolean extendedRetry = literalOverlap && needsBrowser;
            scheduleAssistBrowserHtmlApply(assistant, mergedInfo, extendedRetry);
            if (literalOverlap)
            {
                ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
                logOverlapSideHint(raw, true, hasAssistBrowserControl(assistant), true);
            }
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("pinMergedAdditionalInfo: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** Browser assist для ИР-строк создаётся с задержкой — повторяем setText. */
    private static void scheduleAssistBrowserHtmlApply(ContentAssistant assistant, Object mergedInfo)
    {
        scheduleAssistBrowserHtmlApply(assistant, mergedInfo, false);
    }

    private static void scheduleAssistBrowserHtmlApply(ContentAssistant assistant,
        Object mergedInfo, boolean extendedRetry)
    {
        String rawHtml = IrBslHoverHtml.readHtml(mergedInfo);
        if (rawHtml == null || rawHtml.isEmpty())
            return;
        boolean rawFullDoc = IrBslHoverHtml.isFullHtmlDocument(rawHtml);
        String displayHtml = rawFullDoc ? rawHtml : IrBslHoverHtml.wrapForAssistBrowser(rawHtml);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        final int applyGen = HTML_APPLY_SEQ.incrementAndGet();
        HTML_APPLY_GEN.put(assistant, applyGen);
        logSidePanelRefresh("htmlApplyStart"); //$NON-NLS-1$
        final int maxAttempts = extendedRetry ? 12 : 8;
        final int delayMs = extendedRetry ? 50 : 40;
        final int[] attempts = {0};
        Runnable task = new Runnable() {
            @Override
            public void run()
            {
                if (display.isDisposed() || assistant == null)
                    return;
                Integer currentGen = HTML_APPLY_GEN.get(assistant);
                if (currentGen == null || currentGen.intValue() != applyGen)
                    return;
                boolean applied = tryApplyHtmlToAssistControl(assistant, displayHtml);
                String digest = htmlDigest(displayHtml);
                String prevDigest = LAST_APPLIED_HTML_DIGEST.get(assistant);
                boolean skippedDuplicate = digest != null && !digest.isEmpty()
                    && digest.equals(prevDigest);
                logHtmlApplyPollAttempt(attempts[0], applied, skippedDuplicate);
                IInformationControl anyControl = resolveAnyInformationControl(assistant);
                boolean hasBrowser = anyControl != null
                    && IrBslHoverHtml.findControlBrowser(anyControl) != null;
                if (!applied && attempts[0] < maxAttempts)
                {
                    attempts[0]++;
                    display.timerExec(delayMs, this);
                    return;
                }
                if (extendedRetry)
                {
                    logSidePanelRefresh("htmlApplyDone"); //$NON-NLS-1$
                    String controlClass = describeSideInformationControlClass(assistant);
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
        String name = SmartContentAssistProcessor.parseProposalListName(display);
        if (name.isEmpty())
            return false;
        return name.length() >= prefix.length()
            && name.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** Ручной Ctrl+Space — штатный API JFace. */
    public static boolean showPossibleCompletions(ContentAssistant assistant)
    {
        if (assistant == null)
            return false;
        try
        {
            assistant.showPossibleCompletions();
            return true;
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("showPossibleCompletions ERROR: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    public static void ensureEmptyListAllowed(ContentAssistant assistant, boolean allow)
    {
        if (assistant != null)
            assistant.setShowEmptyList(allow);
    }

    public static void setAssistEmptyMessage(ContentAssistant assistant, String message)
    {
        if (assistant != null)
            assistant.setEmptyMessage(message != null ? message : ""); //$NON-NLS-1$
    }

    /**
     * Возвращает порядковый номер выделенной строки в таблице popup (0-based).
     * -1 если popup не виден, таблица не создана или выделения нет.
     */
    static int readSelectedRowIndex(ContentAssistant assistant)
    {
        if (assistant == null)
            return -1;
        try
        {
            Object popup = getPopupObject(assistant);
            if (popup == null)
                return -1;
            org.eclipse.swt.widgets.Table table =
                (org.eclipse.swt.widgets.Table) Global.getField(popup, "fProposalTable"); //$NON-NLS-1$
            if (table == null || table.isDisposed())
                return -1;
            int idx = table.getSelectionIndex();
            return idx >= 0 ? idx : -1;
        }
        catch (Exception ignored)
        {
            return -1;
        }
    }
}
