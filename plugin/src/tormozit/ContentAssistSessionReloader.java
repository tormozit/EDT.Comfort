package tormozit;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Widget;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сессия assist: prepend к {@code fFilterRunnable} для {@link SmartFilterTracker},
 * Ctrl+Space → переключатель фильтра.
 */
public final class ContentAssistSessionReloader
{
    private static final String DATA_KEY = "ContentAssistSessionReloader.installed"; //$NON-NLS-1$

    private static final IdentityHashMap<SourceViewer, ContentAssistSessionReloader> INSTALLED =
        new IdentityHashMap<>();

    private static final ThreadLocal<ContentAssistant> ACTIVE_ASSISTANT = new ThreadLocal<>();
    private static final ThreadLocal<SourceViewer> ACTIVE_VIEWER = new ThreadLocal<>();
    private static final ThreadLocal<SmartContentAssistProcessor> ACTIVE_PROCESSOR =
        new ThreadLocal<>();
    private static final ThreadLocal<ContentAssistSessionReloader> ACTIVE_RELOADER =
        new ThreadLocal<>();

    /** Текущая сессия assist (не ThreadLocal — {@code AdditionalInfoController} с фонового потока). */
    private static volatile ContentAssistSessionReloader openSessionReloader;

    private final SourceViewer viewer;
    private final ContentAssistant assistant;
    private final SmartContentAssistProcessor processor;
    private final BslXtextEditor bslEditor;
    private final CtrlSpaceFilter ctrlSpaceFilter;
    private final String displayFilterKey;
    private Listener styledTextKeyListener;
    private boolean ctrlSpaceFilterInstalled;
    private boolean styledTextKeyListenerInstalled;
    private final ICompletionListener completionListener;
    private final AtomicInteger irFetchGeneration = new AtomicInteger();
    private final AtomicInteger wordsTableDebounceGen = new AtomicInteger();
    private final List<Runnable> pendingAfterWordsTable = new ArrayList<>();
    private CaretListener sessionCaretListener;
    private volatile boolean wordsTableReady;
    private volatile int wordsTableCaret = -1;
    /** Контекст assist в ИР открыт ({@code КончитьОбработкуКоманды} ещё не вызывали). */
    private volatile boolean irAssistCommandOpen;
    /** Ключ активированной строки assist (для отмены COM при смене активации). */
    private volatile String activeIrDisplayKey;
    /** Время последней активации строки assist (мс). */
    private volatile long irSelectionEpochMs;
    private volatile IrBslCompletionSupport.Snapshot irCompletionSnapshot;
    private final AtomicInteger repinScheduleId = new AtomicInteger();
    private final ConcurrentHashMap<String, String> irMergedHtmlByDisplay = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IrBslCompletionSupport.ActivationDescription>
        irActivationByKey = new ConcurrentHashMap<>();
    private final Set<String> pendingIrActivationKeys = ConcurrentHashMap.newKeySet();
    private volatile IInformationControlCreator assistBrowserCreator;
    /** Ключ ИР-строки, для которой уже обновлена боковая подсказка (анти-шторм). */
    private volatile String irSideHintPublishedKey;
    private volatile boolean pendingPopupRefresh;
    private volatile boolean pendingPopupRefreshIsIr;
    private long irWordsRequestStartedAtMs;
    private volatile boolean irRecomputeFastResponse;
    private volatile String preIrMergeFirstDedupKey;
    /** Каретка ручного Ctrl+Space (popup был закрыт). */
    private volatile int manualIrAssistCaret = -1;
    /** Ожидаем отложенное открытие popup только с ИР. */
    private volatile boolean manualIrAssistPending;
    /** ИР ответил для каретки (в т.ч. пустым). */
    private volatile int irWordsResolvedForCaret = -1;
    /** Popup уже открыт координатором dual-source (ручной Ctrl+Space). */
    private volatile boolean manualDualPopupOpened;
    /** COM-запрос таблицы слов уже отправлен для {@link #wordsTableCaret}. */
    private volatile boolean wordsTableFetchInFlight;

    private static final int WORDS_TABLE_DEBOUNCE_MS = 200;
    /** Последний stamp документа при caret-событии (отличить ввод от стрелок). */
    private long lastCaretDocumentStamp = -1;

    public static void install(SourceViewer viewer, ContentAssistant assistant,
                               SmartContentAssistProcessor processor, BslXtextEditor editor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Widget w = viewer.getTextWidget();
        if (!(w instanceof Control)) return;
        Control control = (Control) w;
        if (INSTALLED.containsKey(viewer))
            return;

        ContentAssistSessionReloader reloader =
            new ContentAssistSessionReloader(viewer, assistant, processor, editor);
        INSTALLED.put(viewer, reloader);
        control.setData(DATA_KEY, Boolean.TRUE);
        reloader.logInstallDiagnostics(viewer);
        ContentAssistDebug.log("install completionListener"); //$NON-NLS-1$
    }

    /** Снятие listener и флага (после {@link ContentAssistPatcher} → native assist). */
    public static void uninstall(SourceViewer viewer, ContentAssistant assistant)
    {
        ContentAssistSessionReloader reloader = INSTALLED.remove(viewer);
        if (reloader != null)
            reloader.detach();
        if (viewer != null && viewer.getTextWidget() != null)
            viewer.getTextWidget().setData(DATA_KEY, null);
    }

    private ContentAssistSessionReloader(SourceViewer viewer, ContentAssistant assistant,
                                         SmartContentAssistProcessor processor,
                                         BslXtextEditor editor)
    {
        this.viewer = viewer;
        this.assistant = assistant;
        this.processor = processor;
        this.bslEditor = editor;
        this.ctrlSpaceFilter = new CtrlSpaceFilter();
        this.displayFilterKey = "tormozit.ctrlSpaceFilter." + System.identityHashCode(viewer); //$NON-NLS-1$
        IInformationControlCreator installBrowserCreator =
            BslCompletionSideHintResolver.resolveAssistBrowserCreator(viewer);
        if (installBrowserCreator == null)
            installBrowserCreator =
                BslCompletionSideHintResolver.resolveAssistBrowserCreatorFromEditor(editor);
        if (installBrowserCreator != null)
            this.assistBrowserCreator = installBrowserCreator;
        // #region agent log
        logInstallCreatorDiagnostics(viewer, installBrowserCreator != null);
        // #endregion

        this.completionListener = new ICompletionListener() {
            @Override
            public void assistSessionStarted(ContentAssistEvent event)
            {
                ContentAssistDebug.resetValidateStats();
                SmartAssistFilterState.reset();
                int caret = ContentAssistPopupSync.syncSessionOffsets(assistant, viewer);
                boolean inLiteral = caret >= 0
                    && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
                if (!event.isAutoActivated && inLiteral && !manualIrAssistPending
                    && ComfortSettings.isReplaceListFiltersEnabled()
                    && IrBslExpressionHtmlSupport.resolveIrSessionForAssist(bslEditor, viewer) != null)
                {
                    // #region agent log
                    ContentAssistDebug.debugModeLog("H1", "assistSessionStarted", "tryBeginLiteral", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "{\"caret\":" + caret + ",\"build\":\"" //$NON-NLS-1$ //$NON-NLS-2$
                            + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                    // #endregion
                    tryBeginManualDualAssist(caret);
                }
                boolean literalManualSession = manualIrAssistPending && caret >= 0 && inLiteral;
                boolean manualDualSession = !event.isAutoActivated && caret >= 0
                    && caret == manualIrAssistCaret;
                boolean preserveLiteralIr = literalManualSession
                    || processor.isIrOnlyManualMode();
                if (!manualDualSession && !preserveLiteralIr)
                    processor.invalidateCache();
                ContentAssistPopupSync.clearSyncState();
                ContentAssistDebug.log("assistSessionStarted auto=" + event.isAutoActivated //$NON-NLS-1$
                    + " processor=" + processorName(event.processor)); //$NON-NLS-1$
                ACTIVE_ASSISTANT.set(assistant);
                ACTIVE_VIEWER.set(viewer);
                ACTIVE_PROCESSOR.set(processor);
                ACTIVE_RELOADER.set(ContentAssistSessionReloader.this);
                openSessionReloader = ContentAssistSessionReloader.this;
                boolean preserveWordsTable = caret >= 0 && wordsTableReady
                    && (caret == wordsTableCaret
                        || (manualIrAssistPending && wordsTableCaret == manualIrAssistCaret));
                // #region agent log
                ContentAssistDebug.debugModeLog("H9", "assistSessionStarted", "session", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"auto\":" + event.isAutoActivated + ",\"caret\":" + caret //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"inLiteral\":" + inLiteral //$NON-NLS-1$
                        + ",\"pending\":" + manualIrAssistPending //$NON-NLS-1$
                        + ",\"irOnly\":" + processor.isIrOnlyManualMode() //$NON-NLS-1$
                        + ",\"preserveWordsTable\":" + preserveWordsTable //$NON-NLS-1$
                        + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                if (!preserveWordsTable)
                {
                    wordsTableReady = false;
                    wordsTableCaret = -1;
                }
                pendingPopupRefresh = false;
                pendingPopupRefreshIsIr = false;
                clearIrSelectionResetState();
                if (!preserveWordsTable)
                {
                    irAssistCommandOpen = false;
                    activeIrDisplayKey = null;
                    irCompletionSnapshot = null;
                    clearIrSideHintCaches();
                }
                IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(caret);
                boolean sessionCreatorOk = fresh != null;
                boolean cachedBeforeFresh = assistBrowserCreator != null;
                if (fresh != null)
                    assistBrowserCreator = fresh;
                // #region agent log
                String resourceSuffix = "null"; //$NON-NLS-1$
                if (viewer.getDocument() instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument xtextDoc)
                {
                    org.eclipse.emf.common.util.URI resourceUri = xtextDoc.getResourceURI();
                    if (resourceUri != null)
                        resourceSuffix = resourceUri.toString();
                }
                ContentAssistDebug.debugModeLog("H21", "assistSessionStarted", "creator", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"viewerHash\":" + System.identityHashCode(viewer) //$NON-NLS-1$
                        + ",\"inLiteral\":" + inLiteral //$NON-NLS-1$
                        + ",\"sessionCreatorOk\":" + sessionCreatorOk //$NON-NLS-1$
                        + ",\"cachedBeforeFresh\":" + cachedBeforeFresh //$NON-NLS-1$
                        + ",\"cachedAfterFresh\":" + (assistBrowserCreator != null) //$NON-NLS-1$
                        + ",\"resourceUri\":\"" + ContentAssistDebug.jsonEscapeForLog(resourceSuffix) //$NON-NLS-1$
                        + "\",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                lastCaretDocumentStamp = -1;
                synchronized (pendingAfterWordsTable)
                {
                    pendingAfterWordsTable.clear();
                }

                if (viewer.getDocument() != null && caret >= 0)
                {
                    SmartFilterTracker.setCurrentFilter(
                        SmartContentAssistProcessor.computeIdentifierFilter(
                            viewer.getDocument(), caret));
                }

                boolean comfortListFilter = ComfortSettings.isReplaceListFiltersEnabled();
                if (!comfortListFilter)
                {
                    ContentAssistPopupSync.uninstallFilterTrackerPrepend(assistant);
                    SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
                }
                else
                {
                    SmartContentAssistProcessor.primeAssistContext(viewer, caret);
                    processor.onAssistSessionContextReady(viewer, caret);
                    ContentAssistPopupSync.installFilterTrackerPrepend(assistant, viewer);
                    installSessionCaretListener();
                    if (!preserveWordsTable && !isWordsTableFetchInFlightForCaret(caret))
                        scheduleWordsTablePreparation(caret);
                }

                if (comfortListFilter)
                {
                    boolean skipPopupSync = processor.isIrOnlyManualMode()
                        || (manualIrAssistPending && inLiteral && wordsTableReady);
                    if (!skipPopupSync)
                        ContentAssistPopupSync.scheduleSessionPopupSync(assistant, viewer, processor);
                }
            }

            @Override
            public void assistSessionEnded(ContentAssistEvent event)
            {
                boolean manualAwait = isManualIrAssistAwaitingWords();
                boolean irOnly = processor != null && processor.isIrOnlyManualMode();
                boolean popupVisible = ContentAssistPopupSync.isPopupVisible(assistant);
                int endCaret = ContentAssistPopupSync.syncSessionOffsets(assistant, viewer);
                boolean inLiteral = endCaret >= 0
                    && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, endCaret);
                if (!inLiteral && !manualAwait
                    && ContentAssistPopupSync.hasAssistBrowserSidePanel(assistant))
                {
                    IInformationControlCreator browserCreator = resolveFreshAssistBrowserCreator(endCaret);
                    if (browserCreator != null)
                        rememberAssistBrowserCreator(browserCreator);
                }
                // #region agent log
                ContentAssistDebug.debugModeLog("H6", "assistSessionEnded", "lifecycle", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"manualAwait\":" + manualAwait //$NON-NLS-1$
                        + ",\"pending\":" + manualIrAssistPending //$NON-NLS-1$
                        + ",\"inFlight\":" + wordsTableFetchInFlight //$NON-NLS-1$
                        + ",\"manualCaret\":" + manualIrAssistCaret //$NON-NLS-1$
                        + ",\"wordsCaret\":" + wordsTableCaret //$NON-NLS-1$
                        + ",\"wordsReady\":" + wordsTableReady + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                ContentAssistDebug.debugModeLog("H16", "assistSessionEnded", "lifecycle", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"manualAwait\":" + manualAwait //$NON-NLS-1$
                        + ",\"irOnly\":" + irOnly //$NON-NLS-1$
                        + ",\"popupVisible\":" + popupVisible //$NON-NLS-1$
                        + ",\"manualDualPopupOpened\":" + manualDualPopupOpened //$NON-NLS-1$
                        + ",\"pending\":" + manualIrAssistPending //$NON-NLS-1$
                        + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                if (!manualAwait)
                {
                    finishIrAssistCommandProcessing();
                    cancelIrEvaluationIfConnected();
                }
                uninstallSessionCaretListener();
                ContentAssistPopupSync.cancelCaretRecompute(viewer);
                ContentAssistPopupSync.cancelSessionPopupSync(viewer);

                if (!manualAwait)
                {
                    clearManualIrAssistState();
                    manualDualPopupOpened = false;
                }

                ACTIVE_ASSISTANT.remove();
                ACTIVE_VIEWER.remove();
                ACTIVE_PROCESSOR.remove();
                ACTIVE_RELOADER.remove();
                if (openSessionReloader == ContentAssistSessionReloader.this)
                    openSessionReloader = null;
                if (!manualAwait)
                {
                    wordsTableReady = false;
                    wordsTableCaret = -1;
                    pendingPopupRefresh = false;
                    pendingPopupRefreshIsIr = false;
                    clearIrSelectionResetState();
                    irWordsRequestStartedAtMs = 0;
                    irAssistCommandOpen = false;
                    activeIrDisplayKey = null;
                    irCompletionSnapshot = null;
                    clearIrSideHintCaches();
                    synchronized (pendingAfterWordsTable)
                    {
                        pendingAfterWordsTable.clear();
                    }
                }

                ContentAssistPopupSync.uninstallFilterTrackerPrepend(assistant);
                ContentAssistPopupSync.clearPendingFilterToggleSelection();
                ContentAssistPopupSync.clearSyncState();
                ContentAssistDebug.log("assistSessionEnded processor=" //$NON-NLS-1$
                    + processorName(event.processor));
                if (!manualAwait)
                    processor.invalidateCache();
                processor.exitIrOnlyManualMode();
                SmartFilterTracker.setCurrentFilter("");
                SmartAssistFilterState.reset();
                SmartContentAssistProcessor.clearLastComputeCaret();
                SmartContentAssistProcessor.clearValidateMatcherCache();
            }

            @Override
            public void selectionChanged(
                org.eclipse.jface.text.contentassist.ICompletionProposal proposal,
                boolean smartToggle)
            {
                if (proposal == null)
                    return;
                org.eclipse.jface.text.contentassist.ICompletionProposal raw =
                    SmartContentAssistProcessor.unwrapProposal(proposal);
                if (!(raw instanceof IrCompletionProposal))
                {
                    clearIrSideHintPublishedKey();
                    return;
                }
                String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(proposal);
                if (cacheKey == null || cacheKey.isEmpty())
                    return;
                if (cacheKey.equals(activeIrDisplayKey))
                    return;
                clearIrSideHintPublishedKey();
                cancelIrEvaluationIfConnected();
            }
        };
        assistant.addCompletionListener(completionListener);
        installCtrlSpaceFilter();
        installStyledTextKeyListener();
    }

    private void logInstallDiagnostics(SourceViewer sourceViewer)
    {
        Widget textWidget = viewer.getTextWidget();
        // #region agent log
        ContentAssistDebug.debugModeLog("H0", "ContentAssistSessionReloader.install", "build", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD //$NON-NLS-1$
                + "\",\"viewerHash\":" + System.identityHashCode(sourceViewer) //$NON-NLS-1$
                + ",\"textWidgetHash\":" + (textWidget != null //$NON-NLS-1$
                    ? System.identityHashCode(textWidget) : -1) //$NON-NLS-1$
                + ",\"comfortOn\":" + ComfortSettings.isReplaceListFiltersEnabled() //$NON-NLS-1$
                + ",\"filterInstalled\":" + ctrlSpaceFilterInstalled //$NON-NLS-1$
                + ",\"textKeyListenerInstalled\":" + styledTextKeyListenerInstalled + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private void logInstallCreatorDiagnostics(SourceViewer sourceViewer, boolean installCreatorOk)
    {
        // #region agent log
        ContentAssistDebug.debugModeLog("H21", "ContentAssistSessionReloader.install", "creator", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"viewerHash\":" + System.identityHashCode(sourceViewer) //$NON-NLS-1$
                + ",\"installCreatorOk\":" + installCreatorOk //$NON-NLS-1$
                + ",\"cachedCreatorOk\":" + (assistBrowserCreator != null) //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private void detach()
    {
        finishIrAssistCommandProcessing();
        cancelIrEvaluationIfConnected();
        uninstallCtrlSpaceFilter();
        uninstallStyledTextKeyListener();
        uninstallSessionCaretListener();
        ContentAssistPopupSync.cancelCaretRecompute(viewer);
        assistant.removeCompletionListener(completionListener);
    }

    private void cancelIrEvaluationIfConnected()
    {
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(bslEditor);
        if (session != null)
            IRSession.cancelActiveEvaluation(session);
    }

    private void finishIrAssistCommandProcessing()
    {
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(bslEditor);
        if (session == null || session.executor == null || session.executor.isShutdown())
            return;
        session.executor.submit(() -> IrBslCompletionSupport.finishAssistCommandProcessing(session));
    }

    private void installSessionCaretListener()
    {
        if (sessionCaretListener != null)
            return;
        if (!(viewer.getTextWidget() instanceof StyledText))
            return;
        StyledText text = (StyledText) viewer.getTextWidget();
        sessionCaretListener = this::onSessionCaretMoved;
        text.addCaretListener(sessionCaretListener);
    }

    private void uninstallSessionCaretListener()
    {
        if (sessionCaretListener == null)
            return;
        if (viewer.getTextWidget() instanceof StyledText)
        {
            StyledText text = (StyledText) viewer.getTextWidget();
            if (!text.isDisposed())
                text.removeCaretListener(sessionCaretListener);
        }
        sessionCaretListener = null;
    }

    private void onSessionCaretMoved(CaretEvent event)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        int caret = event.caretOffset;
        if (caret < 0)
            return;
        long docStamp = -1;
        IDocument doc = viewer.getDocument();
        if (doc instanceof IDocumentExtension4 ext4)
            docStamp = ext4.getModificationStamp();
        boolean typingMovedCaret = docStamp >= 0 && docStamp != lastCaretDocumentStamp;
        if (docStamp >= 0)
            lastCaretDocumentStamp = docStamp;
        SmartContentAssistProcessor.primeFilterTrackerOnly(viewer, caret);
        if (!ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        if (ContentAssistPopupSync.shouldClosePopupAtCaret(viewer, caret))
        {
            ContentAssistPopupSync.hideProposalPopup(assistant);
            return;
        }
        scheduleWordsTablePreparationDebounced(caret);
        if (typingMovedCaret)
            return;
        ContentAssistPopupSync.scheduleRecomputeOnCaretChange(assistant, viewer, processor);
    }

    private void scheduleWordsTablePreparationDebounced(int caret)
    {
        if (caret < 0)
            return;
        int gen = wordsTableDebounceGen.incrementAndGet();
        Control c = (Control) viewer.getTextWidget();
        if (c == null || c.isDisposed())
            return;
        Display display = c.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(WORDS_TABLE_DEBOUNCE_MS, () -> {
            if (gen != wordsTableDebounceGen.get())
                return;
            if (!ContentAssistPopupSync.isPopupVisible(assistant))
                return;
            StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
            if (text == null || text.isDisposed())
                return;
            int liveCaret = text.getCaretOffset();
            if (liveCaret < 0)
                return;
            if (wordsTableReady && liveCaret == wordsTableCaret)
                return;
            scheduleWordsTablePreparation(liveCaret);
        });
    }

    private void installCtrlSpaceFilter()
    {
        Control c = (Control) viewer.getTextWidget();
        if (c == null || c.isDisposed())
            return;
        Display display = c.getDisplay();
        if (display == null || display.isDisposed())
            return;
        if (display.getData(displayFilterKey) != null)
        {
            ctrlSpaceFilterInstalled = true;
            return;
        }
        display.addFilter(SWT.KeyDown, ctrlSpaceFilter);
        display.setData(displayFilterKey, ctrlSpaceFilter);
        ctrlSpaceFilterInstalled = true;
    }

    private void uninstallCtrlSpaceFilter()
    {
        ctrlSpaceFilterInstalled = false;
        Control c = (Control) viewer.getTextWidget();
        if (c == null || c.isDisposed())
            return;
        Display display = c.getDisplay();
        if (display == null || display.isDisposed())
            return;
        Listener installed = (Listener) display.getData(displayFilterKey);
        if (installed != null)
            display.removeFilter(SWT.KeyDown, installed);
        display.setData(displayFilterKey, null);
    }

    private void installStyledTextKeyListener()
    {
        if (styledTextKeyListener != null)
            return;
        StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
        if (text == null || text.isDisposed())
            return;
        styledTextKeyListener = this::onStyledTextKeyDown;
        text.addListener(SWT.KeyDown, styledTextKeyListener);
        styledTextKeyListenerInstalled = true;
    }

    private void uninstallStyledTextKeyListener()
    {
        styledTextKeyListenerInstalled = false;
        if (styledTextKeyListener == null)
            return;
        StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
        if (text != null && !text.isDisposed())
            text.removeListener(SWT.KeyDown, styledTextKeyListener);
        styledTextKeyListener = null;
    }

    private void onStyledTextKeyDown(Event event)
    {
        if (event.type != SWT.KeyDown || !isCtrlSpaceKeyEvent(event))
            return;
        StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
        if (text == null || text.isDisposed())
            return;
        int caret = text.getCaretOffset();
        boolean inLiteral = caret >= 0
            && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        // #region agent log
        ContentAssistDebug.debugModeLog("H11", "StyledTextKeyDown", "keyDown", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + caret + ",\"inLiteral\":" + inLiteral //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (!inLiteral)
            return;
        if (ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (IrBslExpressionHtmlSupport.resolveIrSessionForAssist(bslEditor, viewer) == null)
            return;
        if (caret < 0)
            return;
        tryBeginManualDualAssist(caret);
        event.doit = false;
        // #region agent log
        ContentAssistDebug.debugModeLog("H13", "StyledTextKeyDown", "consumed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"consumed\":true,\"caret\":" + caret + ",\"build\":\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    static boolean isCtrlSpaceKeyEvent(Event event)
    {
        if (event == null)
            return false;
        int sm = event.stateMask;
        if ((sm & SWT.CTRL) == 0 && (sm & SWT.MOD1) == 0)
            return false;
        return event.keyCode == SWT.SPACE
            || event.character == ' '
            || event.character == '\u0000' && event.keyCode == 32;
    }

    public static ContentAssistSessionReloader forViewer(SourceViewer viewer)
    {
        return viewer != null ? INSTALLED.get(viewer) : null;
    }

    public static ContentAssistant getActiveAssistant()
    {
        ContentAssistant fromThread = ACTIVE_ASSISTANT.get();
        if (fromThread != null)
            return fromThread;
        ContentAssistSessionReloader reloader = getActiveReloader();
        return reloader != null ? reloader.assistant : null;
    }

    public static SourceViewer getActiveViewer()
    {
        SourceViewer fromThread = ACTIVE_VIEWER.get();
        if (fromThread != null)
            return fromThread;
        ContentAssistSessionReloader reloader = getActiveReloader();
        return reloader != null ? reloader.viewer : null;
    }

    public static SmartContentAssistProcessor getActiveProcessor()
    {
        SmartContentAssistProcessor fromThread = ACTIVE_PROCESSOR.get();
        if (fromThread != null)
            return fromThread;
        ContentAssistSessionReloader reloader = getActiveReloader();
        return reloader != null ? reloader.processor : null;
    }

    public static ContentAssistSessionReloader getActiveReloader()
    {
        ContentAssistSessionReloader fromThread = ACTIVE_RELOADER.get();
        if (fromThread != null)
            return fromThread;
        return openSessionReloader;
    }

    public BslXtextEditor getBslEditor()
    {
        return bslEditor;
    }

    public SmartContentAssistProcessor getProcessor()
    {
        return processor;
    }

    public IInformationControlCreator getAssistBrowserCreator()
    {
        return assistBrowserCreator;
    }

    public void rememberAssistBrowserCreator(IInformationControlCreator creator)
    {
        if (creator != null)
            assistBrowserCreator = creator;
    }

    /** ИмяТипаКонтекста ИР для подписи «Родитель:» в строковом литерале. */
    public String getIrAssistContextTypeLabel()
    {
        IrBslCompletionSupport.Snapshot snapshot = irCompletionSnapshot;
        if (snapshot == null || snapshot.contextType == null || snapshot.contextType.isEmpty())
            return null;
        return snapshot.contextType;
    }

    public boolean isWordsTableReady()
    {
        return wordsTableReady;
    }

    public int getWordsTableCaret()
    {
        return wordsTableCaret;
    }

    public int nextIrFetchGeneration()
    {
        return irFetchGeneration.incrementAndGet();
    }

    /** Новый gen только при активации другой строки списка — иначе repin/apply не отменяются зря. */
    public int beginIrFetchForDisplay(String displayKey)
    {
        if (displayKey != null && displayKey.equals(activeIrDisplayKey))
            return irFetchGeneration.get();
        activeIrDisplayKey = displayKey;
        irSideHintPublishedKey = null;
        irSelectionEpochMs = System.currentTimeMillis();
        return irFetchGeneration.incrementAndGet();
    }

    /** Смена строки без bump gen (cache hit / повторный выбор). */
    public void noteActiveIrDisplayKey(String displayKey)
    {
        if (displayKey != null && !displayKey.isEmpty())
            activeIrDisplayKey = displayKey;
    }

    public long getIrSelectionEpochMs()
    {
        return irSelectionEpochMs;
    }

    public int getIrFetchGeneration()
    {
        return irFetchGeneration.get();
    }

    public String getIrMergedHtml(String displayKey)
    {
        if (displayKey == null || displayKey.isEmpty())
            return null;
        return irMergedHtmlByDisplay.get(displayKey);
    }

    public void putIrMergedHtml(String displayKey, String mergedHtml)
    {
        if (displayKey == null || displayKey.isEmpty()
            || mergedHtml == null || mergedHtml.isEmpty())
            return;
        irMergedHtmlByDisplay.put(displayKey, mergedHtml);
    }

    public IrBslCompletionSupport.ActivationDescription getIrActivation(String stableCacheKey)
    {
        if (stableCacheKey == null || stableCacheKey.isEmpty())
            return null;
        return irActivationByKey.get(stableCacheKey);
    }

    public void putIrActivation(
        String stableCacheKey, IrBslCompletionSupport.ActivationDescription desc)
    {
        if (stableCacheKey == null || stableCacheKey.isEmpty() || desc == null)
            return;
        irActivationByKey.put(stableCacheKey, desc);
    }

    /** Один COM {@code ОписаниеТекущегоСловаАвтодополнения} на ключ за одну активацию строки. */
    public boolean tryBeginIrActivation(String stableCacheKey)
    {
        if (stableCacheKey == null || stableCacheKey.isEmpty())
            return false;
        if (irActivationByKey.containsKey(stableCacheKey))
            return false;
        return pendingIrActivationKeys.add(stableCacheKey);
    }

    public void endIrActivation(String stableCacheKey)
    {
        if (stableCacheKey != null && !stableCacheKey.isEmpty())
            pendingIrActivationKeys.remove(stableCacheKey);
    }

    public boolean isIrActivationPending(String stableCacheKey)
    {
        return stableCacheKey != null && !stableCacheKey.isEmpty()
            && pendingIrActivationKeys.contains(stableCacheKey);
    }

    private void clearIrSideHintCaches()
    {
        irActivationByKey.clear();
        clearTransientIrSideHintState();
    }

    /** Сброс merged/pending без кэша активации ИР (повторный выбор строки). */
    private void clearTransientIrSideHintState()
    {
        irMergedHtmlByDisplay.clear();
        pendingIrActivationKeys.clear();
        irSideHintPublishedKey = null;
    }

    public boolean isIrSideHintPublishedForKey(String cacheKey)
    {
        return cacheKey != null && !cacheKey.isEmpty() && cacheKey.equals(irSideHintPublishedKey);
    }

    public void markIrSideHintPublished(String cacheKey)
    {
        irSideHintPublishedKey = cacheKey;
    }

    public void clearIrSideHintPublishedKey()
    {
        irSideHintPublishedKey = null;
    }

    /** Отменяет отложенные repin предыдущего элемента. */
    public int nextRepinScheduleId()
    {
        return repinScheduleId.incrementAndGet();
    }

    public boolean isCurrentRepinSchedule(int scheduleId)
    {
        return scheduleId == repinScheduleId.get();
    }

    public boolean isIrAssistCommandOpen()
    {
        return irAssistCommandOpen;
    }

    public void runWhenWordsTableReady(Runnable task)
    {
        if (task == null)
            return;
        if (wordsTableReady)
        {
            task.run();
            return;
        }
        synchronized (pendingAfterWordsTable)
        {
            pendingAfterWordsTable.add(task);
        }
    }

    private boolean isWordsTableFetchInFlightForCaret(int caret)
    {
        return wordsTableFetchInFlight && caret >= 0 && caret == wordsTableCaret;
    }

    private void clearManualIrAssistState()
    {
        manualIrAssistPending = false;
        manualIrAssistCaret = -1;
        manualDualPopupOpened = false;
        irWordsResolvedForCaret = -1;
    }

    private boolean isManualIrAssistAwaitingWords()
    {
        if (!manualIrAssistPending)
            return false;
        if (wordsTableFetchInFlight || !wordsTableReady
            || wordsTableCaret != manualIrAssistCaret)
            return true;
        if (processor != null && processor.isIrOnlyManualMode())
            return true;
        if (manualDualPopupOpened && ContentAssistPopupSync.isPopupVisible(assistant))
            return true;
        return false;
    }

    private void beginManualDualAssist(int caret)
    {
        tryBeginManualDualAssist(caret);
    }

    public void tryBeginManualDualAssist(int caret)
    {
        if (caret < 0)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H1", "tryBeginManualDualAssist", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"skipReason\":\"badCaret\",\"caret\":" + caret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H1", "tryBeginManualDualAssist", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"skipReason\":\"comfortOff\",\"caret\":" + caret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        if (ContentAssistPopupSync.isPopupVisible(assistant))
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H1", "tryBeginManualDualAssist", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"skipReason\":\"popupVisible\",\"caret\":" + caret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        IRSession session = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(bslEditor, viewer);
        if (session == null)
        {
            IDtProject dtProject = bslEditor != null ? Global.getDtProjectFromBslEditor(bslEditor) : null;
            // #region agent log
            ContentAssistDebug.debugModeLog("H5", "tryBeginManualDualAssist", "noSession", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + ",\"editorNull\":" + (bslEditor == null) //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"dtProjectNull\":" + (dtProject == null) //$NON-NLS-1$
                    + ",\"hasKeys\":" + (dtProject != null //$NON-NLS-1$
                        && IRApplication.hasConnectedSessionForKeys(dtProject)) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        manualIrAssistCaret = caret;
        manualIrAssistPending = true;
        manualDualPopupOpened = false;
        irWordsResolvedForCaret = -1;
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        if (assistBrowserCreator == null)
        {
            IInformationControlCreator probed =
                BslCompletionSideHintResolver.probeDelegateBrowserCreator(processor, viewer, caret);
            if (probed != null)
                rememberAssistBrowserCreator(probed);
        }
        // #region agent log
        ContentAssistDebug.debugModeLog("H1", "tryBeginManualDualAssist", "started", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + caret + ",\"inFlight\":" + isWordsTableFetchInFlightForCaret(caret) //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"inLiteral\":" + SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret) //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        boolean irCachedForCaret = wordsTableReady && wordsTableCaret == caret
            && irCompletionSnapshot != null;
        if (irCachedForCaret)
        {
            IDocument doc = viewer != null ? viewer.getDocument() : null;
            processor.onAssistSessionContextReady(viewer, caret);
            processor.primeIrSnapshotForDualAssist(irCompletionSnapshot);
            if (doc != null)
                processor.rememberIrSnapshotCache(irCompletionSnapshot, doc);
        }
        if (!isWordsTableFetchInFlightForCaret(caret) && !irCachedForCaret)
            scheduleWordsTablePreparation(caret);
        boolean literal = SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        if (!irCachedForCaret)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H14", "tryBeginManualDualAssist", //$NON-NLS-1$ //$NON-NLS-2$
                literal ? "deferLiteralOpen" : "deferManualOpen", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"caret\":" + caret + ",\"inFlight\":" + isWordsTableFetchInFlightForCaret(caret) //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"literal\":" + literal + ",\"irCached\":" + irCachedForCaret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            IrCompletionDebug.log("manualDualAssist defer caret=" + caret); //$NON-NLS-1$
            return;
        }
        processor.enterIrOnlyManualMode(caret);
        // #region agent log
        ContentAssistDebug.debugModeLog("H14", "tryBeginManualDualAssist", "openNow", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + caret + ",\"inFlight\":" + isWordsTableFetchInFlightForCaret(caret) //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"literal\":" + literal + ",\"irCached\":" + irCachedForCaret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        openManualDualAssistPopupOnce(caret);
        IrCompletionDebug.log("manualDualAssist caret=" + caret); //$NON-NLS-1$
    }

    /** @deprecated use {@link #tryBeginManualDualAssist} */
    public void tryBeginManualIrAssist(int caret)
    {
        tryBeginManualDualAssist(caret);
    }

    boolean isManualIrAssistPending()
    {
        return manualIrAssistPending;
    }

    private IrBslCompletionSupport.Snapshot buildSnapshotFromRaw(
        int caret, IrBslCompletionSupport.Snapshot raw)
    {
        if (raw != null)
            return new IrBslCompletionSupport.Snapshot(
                caret, raw.contextType, raw.proposals, raw.wordsTransferred, raw.wordsDisplayed);
        return new IrBslCompletionSupport.Snapshot(
            caret, "", new org.eclipse.jface.text.contentassist.ICompletionProposal[0], 0, 0); //$NON-NLS-1$
    }

    private void openManualDualAssistPopupOnce(int expectedCaret)
    {
        if (manualDualPopupOpened)
            return;
        Control widget = (Control) viewer.getTextWidget();
        if (widget == null || widget.isDisposed())
            return;
        int liveCaret = widget instanceof StyledText st ? st.getCaretOffset() : -1;
        if (liveCaret >= 0 && expectedCaret >= 0 && liveCaret != expectedCaret)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H14", "openManualDualAssistPopupOnce", "caretMoved", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"liveCaret\":" + liveCaret + ",\"expectedCaret\":" + expectedCaret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        boolean shown = ContentAssistPopupSync.showPossibleCompletions(assistant);
        manualDualPopupOpened = true;
        boolean popupVisible = shown && ContentAssistPopupSync.isPopupVisible(assistant);
        if (popupVisible)
        {
            ContentAssistPopupSync.ensureAssistBrowserCreatorOnController(assistant, viewer);
            ContentAssistPopupSync.recomputePopupList(assistant, viewer, processor);
            ContentAssistPopupSync.scheduleFilterBarSetup(assistant, viewer, processor);
            widget.getDisplay().asyncExec(() -> finishDeferredLiteralPopupSetup(assistant, viewer));
        }
        // #region agent log
        ContentAssistDebug.debugModeLog("H15", "openManualDualAssistPopupOnce", "show", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + expectedCaret + ",\"shown\":" + shown //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"popupVisible\":" + popupVisible //$NON-NLS-1$
                + ",\"pendingKept\":" + manualIrAssistPending + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private boolean isManualCaretMatch(int snapshotCaret)
    {
        if (manualIrAssistCaret < 0)
            return false;
        if (snapshotCaret == manualIrAssistCaret)
            return true;
        Control widget = (Control) viewer.getTextWidget();
        if (widget instanceof StyledText st && !st.isDisposed())
            return st.getCaretOffset() == manualIrAssistCaret;
        return false;
    }

    private void openDeferredIrAssistPopup(IrBslCompletionSupport.Snapshot snapshot,
        int snapshotCaret)
    {
        Control widget = (Control) viewer.getTextWidget();
        if (widget == null || widget.isDisposed())
            return;
        int liveCaret = widget instanceof StyledText st ? st.getCaretOffset() : snapshotCaret;
        if (liveCaret < 0)
            liveCaret = snapshotCaret;
        processor.enterIrOnlyManualMode(liveCaret);
        processor.onAssistSessionContextReady(viewer, liveCaret);
        processor.primeIrSnapshotForDualAssist(snapshot);
        SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        int irN = snapshot.proposals != null ? snapshot.proposals.length : 0;
        boolean shown = ContentAssistPopupSync.showPossibleCompletions(assistant);
        boolean popupVisible = shown && ContentAssistPopupSync.isPopupVisible(assistant);
        if (popupVisible)
            manualDualPopupOpened = true;
        if (popupVisible && assistant != null && viewer != null && processor != null)
        {
            ContentAssistPopupSync.ensureAssistBrowserCreatorOnController(assistant, viewer);
            ContentAssistPopupSync.recomputePopupList(assistant, viewer, processor);
            ContentAssistPopupSync.scheduleFilterBarSetup(assistant, viewer, processor);
        }
        // #region agent log
        ContentAssistDebug.debugModeLog("H10", "openDeferredIrAssistPopupNow", "show", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"liveCaret\":" + liveCaret + ",\"snapshotCaret\":" + snapshotCaret //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"empty\":" + (irN == 0) + ",\"irN\":" + irN + ",\"shown\":" + shown //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"popupVisible\":" + popupVisible //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        ContentAssistDebug.debugModeLog("H3", "openDeferredIrAssistPopup", "show", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"empty\":" + (irN == 0) + ",\"irN\":" + irN + ",\"shown\":" + shown //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"popupVisible\":" + popupVisible + ",\"syncRecompute\":" + true + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        ContentAssistDebug.debugModeLog("H16", "openDeferredIrAssistPopup", "show", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"irOnly\":" + processor.isIrOnlyManualMode() //$NON-NLS-1$
                + ",\"popupVisible\":" + popupVisible //$NON-NLS-1$
                + ",\"manualDualPopupOpened\":" + manualDualPopupOpened //$NON-NLS-1$
                + ",\"pending\":" + manualIrAssistPending //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        widget.getDisplay().asyncExec(() -> {
            if (assistant == null || viewer == null || processor == null)
                return;
            boolean stillVisible = ContentAssistPopupSync.isPopupVisible(assistant);
            // #region agent log
            ContentAssistDebug.debugModeLog("H16", "openDeferredIrAssistPopup", "asyncCleanup", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"stillVisible\":" + stillVisible //$NON-NLS-1$
                    + ",\"irOnly\":" + processor.isIrOnlyManualMode() //$NON-NLS-1$
                    + ",\"pending\":" + manualIrAssistPending + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            if (stillVisible)
            {
                finishDeferredLiteralPopupSetup(assistant, viewer);
            }
        });
    }

    /** Активация первой строки и pin side hint после deferred / manual open. */
    private void finishDeferredLiteralPopupSetup(ContentAssistant assistant, SourceViewer viewer)
    {
        if (assistant == null || viewer == null || processor == null)
            return;
        if (!ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        ContentAssistPopupSync.ensureInitialIrRowActivation(assistant, viewer);
        ContentAssistPopupSync.pinIrSideHintForCurrentSelection(assistant, viewer);
        manualIrAssistPending = false;
        processor.exitIrOnlyManualMode();
    }

    private IInformationControlCreator resolveFreshAssistBrowserCreator(int literalCaret)
    {
        IInformationControlCreator fresh =
            assistBrowserCreator != null ? assistBrowserCreator : null;
        if (fresh == null)
            fresh = BslCompletionSideHintResolver.resolveAssistBrowserCreator(viewer);
        if (fresh == null)
            fresh = BslCompletionSideHintResolver.resolveAssistBrowserCreatorFromEditor(bslEditor);
        if (fresh == null)
            fresh = BslCompletionSideHintResolver.probeDelegateBrowserCreator(
                processor, viewer, literalCaret);
        if (fresh == null)
            fresh = BslCompletionSideHintResolver.resolveAssistBrowserCreator();
        return fresh;
    }

    private void scheduleWordsTablePreparation(int caret)
    {
        if (bslEditor == null || caret < 0)
            return;
        IRSession session = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(bslEditor, viewer);
        if (session == null)
        {
            IDtProject dtProject = Global.getDtProjectFromBslEditor(bslEditor);
            // #region agent log
            ContentAssistDebug.debugModeLog("H5", "scheduleWordsTablePreparation", "noSession", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + ",\"dtProjectNull\":" + (dtProject == null) //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"hasKeys\":" + (dtProject != null //$NON-NLS-1$
                        && IRApplication.hasConnectedSessionForKeys(dtProject)) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        if (isWordsTableFetchInFlightForCaret(caret))
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H5", "scheduleWordsTablePreparation", "skipInFlight", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        irWordsRequestStartedAtMs = System.currentTimeMillis();
        wordsTableReady = false;
        wordsTableCaret = caret;
        wordsTableFetchInFlight = true;
        clearTransientIrSideHintState();
        irCompletionSnapshot = null;
        boolean closePrevious = irAssistCommandOpen;
        IrBslCompletionSupport.prepareAssistContextAsync(session, bslEditor, caret, false,
            closePrevious, raw -> onWordsTablePrepared(caret, raw));
    }

    private void onWordsTablePrepared(int caret, IrBslCompletionSupport.Snapshot raw)
    {
        wordsTableFetchInFlight = false;
        wordsTableReady = true;
        wordsTableCaret = caret;
        irWordsResolvedForCaret = caret;
        IrBslCompletionSupport.Snapshot snapshot = buildSnapshotFromRaw(caret, raw);
        processor.onAssistSessionContextReady(viewer, caret);
        if (raw != null)
        {
            irAssistCommandOpen = true;
            irCompletionSnapshot = snapshot;
            IDocument doc = viewer != null ? viewer.getDocument() : null;
            processor.rememberIrSnapshotCache(irCompletionSnapshot, doc);
            IrCompletionDebug.log("assist snapshot caret=" + caret //$NON-NLS-1$
                + " ir=" + raw.proposals.length); //$NON-NLS-1$
        }
        else
        {
            irCompletionSnapshot = snapshot;
            IDocument doc = viewer != null ? viewer.getDocument() : null;
            processor.rememberIrSnapshotCache(irCompletionSnapshot, doc);
            IrCompletionDebug.log("assist snapshot caret=" + caret + " ir=0"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BslSideHintDebug.log("wordsTable ready caret=" + caret); //$NON-NLS-1$
        boolean popupVisible = ContentAssistPopupSync.isPopupVisible(assistant);
        // #region agent log
        ContentAssistDebug.debugModeLog("H2", "onWordsTablePrepared", "branch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + caret + ",\"irN\":" + snapshot.proposals.length //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"pending\":" + manualIrAssistPending + ",\"popupVisible\":" + popupVisible //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"rawNull\":" + (raw == null) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        ContentAssistDebug.debugModeLog("H15", "onWordsTablePrepared", "merge", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"caret\":" + caret + ",\"manualCaret\":" + manualIrAssistCaret //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"popupVisible\":" + popupVisible + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (popupVisible)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H7", "onWordsTablePrepared", "apply", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + ",\"manualCaret\":" + manualIrAssistCaret //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"irN\":" + snapshot.proposals.length + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            manualIrAssistPending = false;
            processor.applyIrCompletion(snapshot);
        }
        else if (manualIrAssistPending && isManualCaretMatch(caret))
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H7", "onWordsTablePrepared", "deferred", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + ",\"manualCaret\":" + manualIrAssistCaret //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"irN\":" + snapshot.proposals.length + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            openDeferredIrAssistPopup(snapshot, caret);
        }
        else
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H7", "onWordsTablePrepared", "else", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + ",\"manualCaret\":" + manualIrAssistCaret //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"pending\":" + manualIrAssistPending //$NON-NLS-1$
                    + ",\"caretMatch\":" + isManualCaretMatch(caret) //$NON-NLS-1$
                    + ",\"irN\":" + snapshot.proposals.length + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            processor.applyIrCompletion(snapshot);
        }
        List<Runnable> pending;
        synchronized (pendingAfterWordsTable)
        {
            pending = new ArrayList<>(pendingAfterWordsTable);
            pendingAfterWordsTable.clear();
        }
        for (Runnable task : pending)
            task.run();
    }

    static boolean shouldResetSelectionAfterIrMerge(String newFirstDedupKey)
    {
        ContentAssistSessionReloader reloader = ACTIVE_RELOADER.get();
        if (reloader == null)
            return false;
        if (!reloader.irRecomputeFastResponse)
        {
            clearIrSelectionResetState(reloader);
            return false;
        }
        String pre = reloader.preIrMergeFirstDedupKey;
        String next = newFirstDedupKey != null ? newFirstDedupKey : ""; //$NON-NLS-1$
        boolean firstChanged = pre != null && !pre.isEmpty() && !next.isEmpty()
            && !pre.equalsIgnoreCase(next);
        if (firstChanged)
        {
            long elapsed = System.currentTimeMillis() - reloader.irWordsRequestStartedAtMs;
            IrCompletionDebug.logFastIrMergeReset(elapsed, pre, next);
        }
        else
            IrCompletionDebug.logFastIrMergeSkip("firstUnchanged", pre, next); //$NON-NLS-1$
        clearIrSelectionResetState(reloader);
        return firstChanged;
    }

    private static void clearIrSelectionResetState(ContentAssistSessionReloader reloader)
    {
        if (reloader == null)
            return;
        reloader.irRecomputeFastResponse = false;
        reloader.preIrMergeFirstDedupKey = null;
    }

    private void clearIrSelectionResetState()
    {
        clearIrSelectionResetState(this);
    }

    private void rememberPreIrMergeFirstRow(ContentAssistant assistant)
    {
        preIrMergeFirstDedupKey = ContentAssistPopupSync.readFirstVisibleProposalDedupKey(assistant);
    }

    private void prepareIrRecomputeSelectionReset(boolean irRefresh,
                                                   SmartContentAssistProcessor processor)
    {
        if (irRefresh && processor != null && processor.shouldRefreshIrPopup())
        {
            long elapsed = System.currentTimeMillis() - irWordsRequestStartedAtMs;
            irRecomputeFastResponse = elapsed < WORDS_TABLE_DEBOUNCE_MS;
        }
        else
            irRecomputeFastResponse = false;
    }

    /** Повторная загрузка списка после reconcile (member-access после точки). */
    public static void refreshPopupIfOpen()
    {
        requestPopupRefresh(false);
    }

    /** Обновление popup после прихода слов ИР (сразу или в очередь при recompute). */
    public static void requestIrPopupRefresh()
    {
        requestPopupRefresh(true);
    }

    static void flushPendingPopupRefreshIfAny()
    {
        ContentAssistSessionReloader reloader = ACTIVE_RELOADER.get();
        if (reloader == null || !reloader.pendingPopupRefresh)
            return;
        boolean wasIr = reloader.pendingPopupRefreshIsIr;
        reloader.pendingPopupRefresh = false;
        reloader.pendingPopupRefreshIsIr = false;
        ContentAssistant ca = ACTIVE_ASSISTANT.get();
        SourceViewer viewer = ACTIVE_VIEWER.get();
        SmartContentAssistProcessor processor = ACTIVE_PROCESSOR.get();
        if (ca == null || viewer == null || processor == null)
            return;
        if (wasIr && !processor.shouldRefreshIrPopup())
            return;
        if (!ContentAssistPopupSync.isPopupVisible(ca))
            return;
        if (ContentAssistPopupSync.isRecomputeInProgress())
        {
            reloader.pendingPopupRefresh = true;
            reloader.pendingPopupRefreshIsIr = wasIr;
            return;
        }
        if (wasIr)
        {
            IrCompletionDebug.logIrPopupRefresh("flush"); //$NON-NLS-1$
            ContentAssistPopupSync.invalidateSyncSnapshot();
            reloader.rememberPreIrMergeFirstRow(ca);
        }
        reloader.prepareIrRecomputeSelectionReset(wasIr, processor);
        ContentAssistPopupSync.recomputePopupList(ca, viewer, processor);
    }

    private static void requestPopupRefresh(boolean irOnly)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        ContentAssistant ca = ACTIVE_ASSISTANT.get();
        SourceViewer viewer = ACTIVE_VIEWER.get();
        SmartContentAssistProcessor processor = ACTIVE_PROCESSOR.get();
        ContentAssistSessionReloader reloader = ACTIVE_RELOADER.get();
        if (ca == null || viewer == null || processor == null || reloader == null)
            return;
        if (irOnly && !processor.shouldRefreshIrPopup())
        {
            // #region agent log
            ContentAssistDebug.sessionLog("H3", "requestPopupRefresh", "skipNoIr", "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            // #endregion
            return;
        }
        if (!ContentAssistPopupSync.isPopupVisible(ca))
        {
            // #region agent log
            ContentAssistDebug.sessionLog("H2", "requestPopupRefresh", "skipNotVisible", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"irOnly\":" + irOnly + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        if (ContentAssistPopupSync.isRecomputeInProgress())
        {
            reloader.pendingPopupRefresh = true;
            reloader.pendingPopupRefreshIsIr = irOnly;
            if (irOnly)
                IrCompletionDebug.logIrPopupRefresh("queued"); //$NON-NLS-1$
            return;
        }
        if (irOnly)
        {
            IrCompletionDebug.logIrPopupRefresh("now"); //$NON-NLS-1$
            ContentAssistPopupSync.invalidateSyncSnapshot();
            reloader.rememberPreIrMergeFirstRow(ca);
        }
        reloader.prepareIrRecomputeSelectionReset(irOnly, processor);
        ContentAssistPopupSync.recomputePopupList(ca, viewer, processor);
    }

    public static void scheduleFilterToggleUiSync()
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        ContentAssistant ca = ACTIVE_ASSISTANT.get();
        SourceViewer viewer = ACTIVE_VIEWER.get();
        if (ca == null)
            return;
        Control c = null;
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(ca);
            if (popup != null)
            {
                org.eclipse.swt.widgets.Shell shell =
                    ContentAssistPopupSync.getProposalShell(popup);
                if (shell != null && !shell.isDisposed())
                    c = shell;
            }
        }
        catch (Exception ignored) {}

        if (c == null)
            c = org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().getActiveShell();
        if (c == null || c.isDisposed())
            return;
        SmartContentAssistProcessor processor = ACTIVE_PROCESSOR.get();
        c.getDisplay().asyncExec(() -> {
            ContentAssistPopupUi.syncFilterToggle(ca, viewer);
            if (ca != null && viewer != null && processor != null
                && ContentAssistPopupSync.isPopupVisible(ca))
                ContentAssistPopupSync.recomputePopupList(ca, viewer, processor);
        });
    }

    static CtrlSpaceFilter ctrlSpaceFilter(ContentAssistant assistant)
    {
        ContentAssistSessionReloader reloader = getActiveReloader();
        return reloader != null ? reloader.ctrlSpaceFilter : null;
    }

    private static String processorName(IContentAssistProcessor p)
    {
        if (p == null)
            return "null";
        if (p instanceof SmartContentAssistProcessor)
            return "Smart→" + ((SmartContentAssistProcessor) p).getDelegate().getClass().getSimpleName();
        return p.getClass().getSimpleName();
    }

    /** Ctrl+Space: при закрытом popup — запрос ИР; при открытом — переключение фильтра. */
    final class CtrlSpaceFilter implements Listener
    {
        @Override
        public void handleEvent(Event event)
        {
            if (event.type != SWT.KeyDown)
                return;
            if (!isCtrlSpaceKeyEvent(event))
                return;
            StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
            int probeCaret = text != null && !text.isDisposed() ? text.getCaretOffset() : -1;
            if (probeCaret >= 0
                && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, probeCaret))
            {
                if (ContentAssistPopupSync.isPopupVisible(assistant))
                {
                    event.doit = false;
                    return;
                }
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                if (IrBslExpressionHtmlSupport.resolveIrSessionForAssist(bslEditor, viewer) == null)
                    return;
                if (text == null || text.isDisposed())
                    return;
                int caret = text.getCaretOffset();
                if (caret < 0)
                    return;
                // #region agent log
                ContentAssistDebug.debugModeLog("H1", "CtrlSpaceFilter", "literalManualAssist", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"caret\":" + caret + ",\"build\":\"" //$NON-NLS-1$ //$NON-NLS-2$
                        + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                beginManualDualAssist(caret);
                event.doit = false;
                // #region agent log
                ContentAssistDebug.debugModeLog("H13", "CtrlSpaceFilter", "consumed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"consumed\":true,\"caret\":" + caret + ",\"literal\":true}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return;
            }
            // #region agent log
            ContentAssistDebug.debugModeLog("H11", "CtrlSpaceFilter", "keyDown", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + probeCaret //$NON-NLS-1$
                    + ",\"inLiteral\":" + (probeCaret >= 0 //$NON-NLS-1$
                        && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, probeCaret)) //$NON-NLS-1$
                    + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            if (ContentAssistPopupSync.isPopupVisible(assistant))
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H11", "CtrlSpaceFilter", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"skipReason\":\"popupVisible\",\"caret\":" + probeCaret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                // #region agent log
                ContentAssistDebug.debugModeLog("H8", "CtrlSpaceFilter", "popupVisible", "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                // #endregion
                event.doit = false;
                return;
            }
            if (!ComfortSettings.isReplaceListFiltersEnabled())
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H11", "CtrlSpaceFilter", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"skipReason\":\"comfortOff\",\"caret\":" + probeCaret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return;
            }
            if (IrBslExpressionHtmlSupport.resolveIrSessionForAssist(bslEditor, viewer) == null)
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H11", "CtrlSpaceFilter", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"skipReason\":\"noIrSession\",\"caret\":" + probeCaret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                // #region agent log
                ContentAssistDebug.debugModeLog("H5", "CtrlSpaceFilter", "noIrSession", "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                // #endregion
                return;
            }
            if (text == null || text.isDisposed())
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H11", "CtrlSpaceFilter", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"skipReason\":\"noText\",\"caret\":" + probeCaret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return;
            }
            int caret = text.getCaretOffset();
            if (caret < 0)
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H11", "CtrlSpaceFilter", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"skipReason\":\"badCaret\",\"caret\":" + caret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                // #region agent log
                ContentAssistDebug.debugModeLog("H8", "CtrlSpaceFilter", "badCaret", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"caret\":" + caret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return;
            }
            // #region agent log
            ContentAssistDebug.debugModeLog("H1", "CtrlSpaceFilter", "manualAssist", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + ",\"inLiteral\":" //$NON-NLS-1$ //$NON-NLS-2$
                    + SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret)
                    + "}"); //$NON-NLS-1$
            // #endregion
            beginManualDualAssist(caret);
            event.doit = false;
            // #region agent log
            ContentAssistDebug.debugModeLog("H13", "CtrlSpaceFilter", "consumed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"consumed\":true,\"caret\":" + caret + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
        }
    }
}
