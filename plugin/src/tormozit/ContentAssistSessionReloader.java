package tormozit;

import org.eclipse.core.runtime.ListenerList;
import org.eclipse.jface.text.AbstractDocument;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.link.ProposalPosition;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionListenerExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
    /** Eclipse command Ctrl+Space в редакторе (обходит Display filter). */
    static final String CONTENT_ASSIST_PROPOSALS_COMMAND =
        "org.eclipse.ui.edit.text.contentAssist.proposals"; //$NON-NLS-1$

    private static final ThreadLocal<Boolean> LITERAL_REPEAT_FROM_COMMAND = new ThreadLocal<>();
    private static volatile long lastLiteralToggleMs;
    private static final AtomicInteger contentAssistCommandListenerRefs = new AtomicInteger();
    private static IExecutionListener contentAssistCommandListener;
    private static final IdentityHashMap<SourceViewer, ContentAssistSessionReloader> INSTALLED =
        new IdentityHashMap<>();
    private boolean pendingAutoOpen = false;

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
    private final TextEditorFacade facade;
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
    private volatile int preIrMergeSelectedRow = -1;
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
    /** H54: порядковый номер fetch words table (диагностика гонки callback). */
    private volatile int wordsTableFetchDiagSeq;
    /** Счётчик literal-open для H30 (fix10). */
    private volatile int literalOpenGen;
    private volatile long literalOpenStartedAtMs = -1;
    /** Browser creator уже применён в open-path literal (fix10). */
    private volatile boolean literalBrowserPrimed;
    /** {@code finishDeferredLiteralPopupSetup}: pin отложен после {@code selected()}. */
    private volatile boolean literalFinishPinPending;
    /** {@code false} от {@link #beginLiteralOpenTracking} до конца finish-path (fix10d). */
    private volatile boolean literalOpenSetupComplete;
    /** Snapshot ИР уже применён для caret (дедуп onWordsTablePrepared). */
    private volatile int irSnapshotAppliedCaret = -1;
    private volatile int irSnapshotAppliedIrCount = -1;
    /** Смена IR-only → merged full list — принудительный replace popup. */
    private volatile int irMergeGeneration;
    private volatile boolean irMergeGenerationBumpPending;
    /**
     * Флаг: сессия assist только что завершилась (assistSessionEnded уже вызван,
     * но proposal.apply() ещё не завершился). documentChanged, которое придёт
     * синхронно в этом же event-цикле, — это вставка proposal, а не ручной ввод.
     * asyncExec сбросит флаг после обработки текущего SWT-события.
     */
    private volatile boolean suppressDocumentAutoOpenAfterSession;
    /**
     * Штатный {@code BslDocumentListener} на время apply: если {@code DataEvent.doIt}
     * упадёт до {@code addDocumentListener}, listener останется снятым — запомнили
     * экземпляр в aboutToBeChanged, чтобы вернуть на документ.
     */
    private volatile IDocumentListener pendingBslDocumentListener;
    /**
     * После insert: objects отфильтрованы (нет штатного ParametersHover) —
     * {@code executeCommand(InvocationParametersHover)}, если каретка внутри {@code ()}.
     */
    private volatile boolean pendingShowParamHintAfterInsert;
    private volatile int pendingParamHintDesiredCaret = -1;
    /**
     * Ввод {@code (} или {@code ,} — открыть подсказку параметров метода,
     * если она не открыта (багфикс штатной логики).
     */
    private volatile boolean pendingParamHintOnChar;
    private volatile int pendingParamHintOnCharCaret = -1;
    /** Поколение отложенного param hint — отмена повторного timerExec. */
    private final AtomicInteger paramHintPostGen = new AtomicInteger();
    /** Автооткрытие assist (порт ИР ПриНажатииКлавишиАвтодополнение). */
    private volatile boolean completionAutoOpenPending;
    private volatile int completionAutoOpenCaret = -1;
    private volatile boolean completionAutoOpenEdtOpened;
    private volatile boolean completionAutoOpenIrScheduled;
    private final AtomicInteger completionAutoOpenScheduleGen = new AtomicInteger();
    private final AtomicInteger completionAutoOpenSeq = new AtomicInteger();
    private volatile int completionAutoOpenActiveSeq;
    private VerifyKeyListener completionAutoOpenVerifyListener;
    private IDocumentListener completionAutoOpenDocumentListener;
    private volatile boolean completionAutoOpenAwaitingLogged;

    private static final int WORDS_TABLE_DEBOUNCE_MS = 200;
    /** Задержка flush pending recompute после literal-open (fix10). */
    private static final int LITERAL_OPEN_FLUSH_GUARD_MS = 200;
    /** Последний stamp документа при caret-событии (отличить ввод от стрелок). */
    private long lastCaretDocumentStamp = -1;
    /** Один timerExec на flush в guard-окне literal-open (не asyncExec-шторм). */
    private final AtomicInteger literalFlushTimerGen = new AtomicInteger();
    private volatile boolean literalFlushTimerActive;

    public static void install(SourceViewer viewer, ContentAssistant assistant,
                               SmartContentAssistProcessor processor, TextEditorFacade facade)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Widget w = viewer.getTextWidget();
        if (!(w instanceof Control)) return;
        Control control = (Control) w;
        if (INSTALLED.containsKey(viewer))
            return;

        ContentAssistSessionReloader reloader =
            new ContentAssistSessionReloader(viewer, assistant, processor, facade);
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

    /** Отмена отложенных timerExec/param-hint перед закрытием EDT. */
    public static void prepareShutdown()
    {
        for (ContentAssistSessionReloader reloader :
                new java.util.ArrayList<>(INSTALLED.values()))
        {
            if (reloader != null)
                reloader.prepareShutdownInstance();
        }
    }

    private void prepareShutdownInstance()
    {
        paramHintPostGen.incrementAndGet();
        literalFlushTimerGen.incrementAndGet();
        literalFlushTimerActive = false;
        cancelCompletionAutoOpenTimers(completionAutoOpenActiveSeq, "shutdown"); //$NON-NLS-1$
        if (viewer == null)
            return;
        ContentAssistPopupSync.cancelSessionPopupSync(viewer);
        ContentAssistPopupSync.cancelFilterBarSetup(viewer);
        ContentAssistPopupSync.cancelCaretRecompute(viewer);
        if (assistant != null)
            ContentAssistPopupSync.hideProposalPopup(assistant);
    }

    private ContentAssistSessionReloader(SourceViewer viewer, ContentAssistant assistant,
                                         SmartContentAssistProcessor processor,
                                         TextEditorFacade facade)
    {
        this.viewer = viewer;
        this.assistant = assistant;
        this.processor = processor;
        this.facade = facade;
        this.bslEditor = facade instanceof BslTextEditorFacade bf ? bf.getBslEditor() : null;
        this.ctrlSpaceFilter = new CtrlSpaceFilter();
        this.displayFilterKey = "tormozit.ctrlSpaceFilter." + System.identityHashCode(viewer); //$NON-NLS-1$
        IInformationControlCreator installBrowserCreator =
            BslCompletionSideHintResolver.resolveAssistBrowserCreator(viewer);
        if (installBrowserCreator == null && bslEditor != null)
            installBrowserCreator =
                BslCompletionSideHintResolver.resolveAssistBrowserCreatorFromEditor(bslEditor);
        if (installBrowserCreator == null && bslEditor != null)
            installBrowserCreator = BslCompletionSideHintResolver
                .resolveAssistBrowserCreatorCold(bslEditor, viewer, processor).creator;
        if (installBrowserCreator == null && bslEditor != null)
            installBrowserCreator = BslCompletionSideHintResolver.primeRspHoverCreator(
                viewer, -1, bslEditor);
        if (installBrowserCreator != null)
        {
            this.assistBrowserCreator = installBrowserCreator;
            if (bslEditor != null)
                BslCompletionSideHintResolver.rememberEditorBrowserCreator(bslEditor, installBrowserCreator);
        }
this.completionListener = new CompletionListenerAdapter() {
            @Override
            public void assistSessionStarted(ContentAssistEvent event)
            {
                assistSessionStartedImpl(event);
            }

            private void assistSessionStartedImpl(ContentAssistEvent event)
            {
                ContentAssistDebug.resetValidateStats();
                SmartAssistFilterState.reset();
                int caret = ContentAssistPopupSync.syncSessionOffsets(assistant, viewer);
                boolean inLiteral = caret >= 0
                    && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
                if (!event.isAutoActivated && inLiteral && !manualIrAssistPending
                    && !completionAutoOpenPending && !completionAutoOpenIrScheduled
                    && !processor.isIrOnlyManualMode()
                    && ComfortSettings.isReplaceListFiltersEnabled()
                    && IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer) != null)
                {
tryBeginManualDualAssist(caret);
                }
                boolean inMember = false;
                if (caret >= 0 && !inLiteral && viewer.getDocument() != null)
                {
                    inMember = SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(
                        viewer.getDocument(), caret) >= 0;
                }
                if (!event.isAutoActivated && inMember
                    && ComfortSettings.isReplaceListFiltersEnabled()
                    && IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer) != null)
                {
                    ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
}
                boolean literalManualSession = manualIrAssistPending && caret >= 0 && inLiteral;
                boolean manualDualSession = !event.isAutoActivated && caret >= 0
                    && caret == manualIrAssistCaret;
                boolean preserveLiteralIr = literalManualSession
                    || processor.isIrOnlyManualMode();
                boolean preserveAutoOpen = completionAutoOpenPending || completionAutoOpenEdtOpened;
                if (!manualDualSession && !preserveLiteralIr && !preserveAutoOpen)
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
                        || (manualIrAssistPending && wordsTableCaret == manualIrAssistCaret)
                        || (preserveAutoOpen && wordsTableCaret == completionAutoOpenCaret));
                int openGen = -1;
                if (inLiteral)
                {
                    if (literalOpenStartedAtMs < 0)
                        openGen = beginLiteralOpenTracking();
                    else
                        openGen = literalOpenGen;
                }
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
                    boolean autoOpenIrPending = completionAutoOpenIrScheduled
                        && caret == completionAutoOpenCaret;
                    if (!preserveWordsTable && !isWordsTableFetchInFlightForCaret(caret)
                        && !autoOpenIrPending)
                        scheduleWordsTablePreparation(caret);
                    if (autoOpenIrPending)
                    {
                        ContentAssistDebug.debugModeLog("H12", "assistSessionStarted", //$NON-NLS-1$ //$NON-NLS-2$
                            "skipDuplicateIrFetch", //$NON-NLS-1$
                            "{\"caret\":" + caret + ",\"autoOpenCaret\":" + completionAutoOpenCaret //$NON-NLS-1$ //$NON-NLS-2$
                                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }

                if (comfortListFilter)
                {
                    // irOnly (литерал / старый auto): полный sync пропущен — только панель.
                    // Member-access auto с ИР идёт через memberEdtFirst (не irOnly) →
                    // scheduleSessionPopupSync → inplaceCollapse (сохраняет DataEvent).
                    boolean skipPopupSync = processor.isIrOnlyManualMode()
                        || (manualIrAssistPending && inLiteral && wordsTableReady);
                    if (!skipPopupSync)
                        ContentAssistPopupSync.scheduleSessionPopupSync(assistant, viewer, processor);
                    else
                        ContentAssistPopupSync.scheduleFilterBarSetup(assistant, viewer, processor);
                }
            }

            @Override
            public void assistSessionRestarted(ContentAssistEvent event)
            {
                int caret = ContentAssistPopupSync.syncSessionOffsets(assistant, viewer);
                boolean inLiteral = caret >= 0
                    && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
                boolean popupVisible = ContentAssistPopupSync.isPopupVisible(assistant);
                boolean irConnected = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(
                    facade, viewer) != null;
if (Boolean.TRUE.equals(LITERAL_REPEAT_FROM_COMMAND.get()))
                    return;
                if (!ComfortSettings.isReplaceListFiltersEnabled() || !popupVisible
                    || !inLiteral)
                    return;
                if (event.isAutoActivated)
                    return;
                tryLiteralRepeatFilterToggle("assistSessionRestarted"); //$NON-NLS-1$
            }

            @Override
            public void assistSessionEnded(ContentAssistEvent event)
            {
                assistSessionEndedImpl(event);
            }

            private void assistSessionEndedImpl(ContentAssistEvent event)
            {
                boolean manualAwait = isManualIrAssistAwaitingWords();
                boolean preserveOnEnd = manualAwait || isCompletionAutoOpenAwaitingIr();
                boolean popupVisible = ContentAssistPopupSync.isPopupVisible(assistant);
                int endCaret = ContentAssistPopupSync.syncSessionOffsets(assistant, viewer);
boolean inLiteral = endCaret >= 0
                    && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, endCaret);
                if (!inLiteral && !preserveOnEnd
                    && ContentAssistPopupSync.hasAssistBrowserSidePanel(assistant))
                {
                    IInformationControlCreator browserCreator = resolveFreshAssistBrowserCreator(endCaret);
                    if (browserCreator != null)
                    {
                        rememberAssistBrowserCreator(browserCreator);
                        BslCompletionSideHintResolver.rememberEditorBrowserCreator(
                            bslEditor, browserCreator);
                    }
                }
                if (!preserveOnEnd)
                {
                    finishIrAssistCommandProcessing();
                    cancelIrEvaluationIfConnected();
                }
                uninstallSessionCaretListener();
                ContentAssistPopupSync.cancelCaretRecompute(viewer);
                ContentAssistPopupSync.cancelSessionPopupSync(viewer);

                // proposal.apply() выполняется после assistSessionEnded — подавляем
                // авто-открытие, которое иначе сработало бы на вставленный текст.
                suppressDocumentAutoOpenAfterSession = true;
                Control suppressWidget = (Control) viewer.getTextWidget();
                if (suppressWidget != null && !suppressWidget.isDisposed())
                {
                    Display suppressDisplay = suppressWidget.getDisplay();
                    if (suppressDisplay != null && !suppressDisplay.isDisposed())
                    {
suppressDisplay.asyncExec(
                            () -> suppressDocumentAutoOpenAfterSession = false);
                    }
                }

                if (!preserveOnEnd)
                {
                    clearManualIrAssistState();
                    clearCompletionAutoOpenState("sessionEnd", completionAutoOpenActiveSeq); //$NON-NLS-1$
                    cancelCompletionAutoOpenTimers(completionAutoOpenActiveSeq, "sessionEnd"); //$NON-NLS-1$
                    manualDualPopupOpened = false;
                }

                ACTIVE_ASSISTANT.remove();
                ACTIVE_VIEWER.remove();
                ACTIVE_PROCESSOR.remove();
                ACTIVE_RELOADER.remove();
                if (openSessionReloader == ContentAssistSessionReloader.this)
                    openSessionReloader = null;
                if (!preserveOnEnd)
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
                    clearLiteralOpenState();
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
                if (!preserveOnEnd)
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
        installCompletionAutoOpenVerifyListener();
        installCompletionAutoOpenDocumentListener();
        logAutoOpenReadyDiagnostics();
        installContentAssistCommandListener();
    }

    private void logInstallDiagnostics(SourceViewer sourceViewer)
    {
        Widget textWidget = viewer.getTextWidget();
}

    private void logInstallCreatorDiagnostics(SourceViewer sourceViewer, boolean installCreatorOk)
    {
}

    private void detach()
    {
        finishIrAssistCommandProcessing();
        cancelIrEvaluationIfConnected();
        uninstallCtrlSpaceFilter();
        uninstallStyledTextKeyListener();
        uninstallCompletionAutoOpenVerifyListener();
        uninstallCompletionAutoOpenDocumentListener();
        uninstallSessionCaretListener();
        uninstallContentAssistCommandListener();
        ContentAssistPopupSync.cancelCaretRecompute(viewer);
        assistant.removeCompletionListener(completionListener);
    }

    private void cancelIrEvaluationIfConnected()
    {
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(facade);
        if (session != null)
            IRSession.cancelActiveEvaluation(session);
    }

    private void finishIrAssistCommandProcessing()
    {
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(facade);
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
        onSessionCaretMovedImpl(event);
    }

    private void onSessionCaretMovedImpl(CaretEvent event)
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
            if (shouldSkipLiteralIrRefetch(liveCaret))
                return;
            scheduleWordsTablePreparation(liveCaret);
        });
    }

    private boolean shouldSkipLiteralIrRefetch(int caret)
    {
        if (!ContentAssistPopupSync.isPopupVisible(assistant))
            return false;
        if (completionAutoOpenPending || manualIrAssistPending)
            return false;
        if (!SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret))
            return false;
        return processor.isIrWordsResolvedForLiteralCaret(viewer, caret);
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

    private void installCompletionAutoOpenVerifyListener()
    {
        if (completionAutoOpenVerifyListener != null)
            return;
        StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
        if (text == null || text.isDisposed())
            return;
        completionAutoOpenVerifyListener = this::onVerifyKeyForCompletionAutoOpen;
        text.addVerifyKeyListener(completionAutoOpenVerifyListener);
    }

    private void uninstallCompletionAutoOpenVerifyListener()
    {
        if (completionAutoOpenVerifyListener == null)
            return;
        StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
        if (text != null && !text.isDisposed())
            text.removeVerifyKeyListener(completionAutoOpenVerifyListener);
        completionAutoOpenVerifyListener = null;
    }

    private void installCompletionAutoOpenDocumentListener()
    {
        if (completionAutoOpenDocumentListener != null)
            return;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (doc == null)
            return;
        completionAutoOpenDocumentListener = new IDocumentListener() {
            @Override
            public void documentAboutToBeChanged(DocumentEvent event)
            {
                prepareStockLinkedModeBeforeAssistInsert(event);
            }

            @Override
            public void documentChanged(DocumentEvent event)
            {
                scheduleEnsureBslDocumentListenerAfterAssistInsert(event);
                maybeShowParamHintOnChar();
                onDocumentChangedForCompletionAutoOpen(event);
            }
        };
        doc.addDocumentListener(completionAutoOpenDocumentListener);
    }

    /**
     * До штатного {@code BslDocumentListener.documentChanged} → {@code DataEvent.doIt}:
     * убрать из {@code objects} EObject без {@link IResourceServiceProvider} (NPE в
     * {@code BslCommentUiUtils.parseTemplateComment}), чтобы {@code LinkedModeUI.enter()}
     * отработал штатно. Запомнить listener на случай throw до re-add.
     */
    private void prepareStockLinkedModeBeforeAssistInsert(DocumentEvent event)
    {
        pendingBslDocumentListener = null;
        pendingShowParamHintAfterInsert = false;
        pendingParamHintDesiredCaret = -1;
        if (event == null || event.getDocument() == null || event.getText() == null)
            return;
        if (!isAssistProposalInsertInProgress())
            return;
        String text = event.getText();
        if (text.isEmpty() || text.indexOf('(') < 0)
            return;
        IDocument doc = event.getDocument();
        IDocumentListener bslListener = findBslDocumentListener(doc);
        if (bslListener == null)
        {
return;
        }
        pendingBslDocumentListener = bslListener;
        DataEventDiag before = readDataEventDiag(bslListener, text);
        int clearedChoices = clearProposalPositionChoices(bslListener, text);
        int filtered = sanitizeDataEventObjectsWithoutRsp(bslListener, text);
        DataEventDiag after = readDataEventDiag(bslListener, text);
        int open = text.indexOf('(');
        int desiredCaret = open >= 0 ? event.getOffset() + open + 1 : -1;
        // Штатный ParametersHover строится из objects; если все отфильтрованы
        // (прикладной метод / bm:// без RSP) — подсказку параметров откроем сами.
        if (after.objectsSize == 0 && before.hasKey && desiredCaret >= 0)
        {
            pendingShowParamHintAfterInsert = true;
            pendingParamHintDesiredCaret = desiredCaret;
        }
schedulePostDoItLinkedModeDiag(doc, event.getOffset(), text);
    }

    /**
     * Подавить popup локальных переменных: {@code ProposalPosition.getChoices()} → пустой массив.
     * Штатный {@code BslSelectionChangedListener} иначе открывает список choices рядом с
     * {@code ParametersHoverInfoControl}.
     *
     * @return число очищенных ProposalPosition
     */
    private static int clearProposalPositionChoices(IDocumentListener bslListener,
        String replacementText)
    {
        if (bslListener == null || replacementText == null)
            return 0;
        try
        {
            Field mapField = bslListener.getClass().getDeclaredField("map"); //$NON-NLS-1$
            mapField.setAccessible(true);
            Object mapObj = mapField.get(bslListener);
            if (!(mapObj instanceof Map<?, ?> map))
                return 0;
            Object dataEvent = map.get(replacementText);
            if (dataEvent == null)
                return 0;
            Field allInfoField = dataEvent.getClass().getDeclaredField("allInfo"); //$NON-NLS-1$
            allInfoField.setAccessible(true);
            Object allInfoObj = allInfoField.get(dataEvent);
            if (!(allInfoObj instanceof List<?> allInfo) || allInfo.isEmpty())
                return 0;
            Field proposalsField = ProposalPosition.class.getDeclaredField("fProposals"); //$NON-NLS-1$
            proposalsField.setAccessible(true);
            int cleared = 0;
            ICompletionProposal[] empty = new ICompletionProposal[0];
            for (Object pos : allInfo)
            {
                if (!(pos instanceof ProposalPosition))
                    continue;
                Object current = proposalsField.get(pos);
                if (current instanceof ICompletionProposal[] arr && arr.length == 0)
                    continue;
                proposalsField.set(pos, empty);
                cleared++;
            }
            return cleared;
        }
        catch (Exception ex)
        {
            ContentAssistDebug.debugModeLog("H-INSERT", "clearProposalChoices", "error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"ex\":\"" + ContentAssistDebug.jsonEscapeForLog(String.valueOf(ex)) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            return 0;
        }
    }

    /**
     * Ввод {@code (} или {@code ,}: открыть подсказку параметров метода,
     * если она не открыта (аналогично {@link #maybeShowParamHintAfterInsert}).
     */
    private void maybeShowParamHintOnChar()
    {
        if (!pendingParamHintOnChar)
            return;
        pendingParamHintOnChar = false;
        int caret = pendingParamHintOnCharCaret;
        pendingParamHintOnCharCaret = -1;
        if (caret < 0)
            return;
        if (Boolean.TRUE.equals(SmartCompletionProposal.PROPOSAL_APPLY_IN_PROGRESS.get()))
            return;
        if (suppressDocumentAutoOpenAfterSession)
            return;
        if (isParamHoverShellVisible() || isParamHoverInfoControlVisible())
            return;
        pendingShowParamHintAfterInsert = true;
        pendingParamHintDesiredCaret = caret;
        maybeShowParamHintAfterInsert(caret);
    }

    /**
     * Для прикладных методов (objects отфильтрованы) штатный ParametersHover из LinkedMode
     * не поднимается. Ждём AST ({@code Invocation} у каретки), затем один
     * {@code executeCommand(InvocationParametersHover)}.
     */
    private void maybeShowParamHintAfterInsert(int caret)
    {
        if (!pendingShowParamHintAfterInsert)
            return;
        int desired = pendingParamHintDesiredCaret;
        pendingShowParamHintAfterInsert = false;
        pendingParamHintDesiredCaret = -1;
        if (desired < 0 || caret != desired)
        {
return;
        }
        StyledText widget = viewer != null ? viewer.getTextWidget() : null;
        Display display = widget != null && !widget.isDisposed() ? widget.getDisplay() : null;
        if (display == null || display.isDisposed())
            return;
final int desiredCaret = desired;
        final int gen = paramHintPostGen.incrementAndGet();
        final long startedMs = System.currentTimeMillis();
        display.timerExec(0, () -> pollAstReadyThenShowParamHint(desiredCaret, gen, 0, startedMs));
    }

    private static final int PARAM_HINT_AST_POLL_MS = 20;
    private static final int PARAM_HINT_AST_TIMEOUT_MS = 500;

    /**
     * Пока Xtext не видит {@code Invocation}/{@code OperatorStyleCreator} у каретки —
     * {@code InvocationParametersHover} молча ничего не показывает. Опрос AST, затем
     * один {@code executeCommand}.
     */
    private void pollAstReadyThenShowParamHint(int desiredCaret, int gen, int attempt,
        long startedMs)
    {
        if (gen != paramHintPostGen.get())
            return;
        StyledText st = viewer != null ? viewer.getTextWidget() : null;
        Display display = st != null && !st.isDisposed() ? st.getDisplay() : null;
        if (display == null || display.isDisposed())
            return;
        int caret = st.getCaretOffset();
        if (caret != desiredCaret)
        {
return;
        }
        if (isParamHoverShellVisible())
        {
            paramHintPostGen.incrementAndGet();
return;
        }
        boolean astReady = isInvocationAstReadyAt(desiredCaret);
        long waitMs = System.currentTimeMillis() - startedMs;
        if (!astReady && waitMs < PARAM_HINT_AST_TIMEOUT_MS)
        {
display.timerExec(PARAM_HINT_AST_POLL_MS,
                () -> pollAstReadyThenShowParamHint(desiredCaret, gen, attempt + 1, startedMs));
            return;
        }
runInvocationParametersHoverCommand(desiredCaret, gen, attempt);
    }

    /**
     * Как {@code InvocationParametersHoverHandler}: у offset есть Invocation /
     * OperatorStyleCreator (модель уже пересчитана после insert).
     */
    private boolean isInvocationAstReadyAt(int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (!(doc instanceof IXtextDocument xdoc))
            return false;
        try
        {
            Boolean ready = xdoc.readOnly(
                (IUnitOfWork<Boolean, XtextResource>) resource -> {
                    if (resource == null)
                        return Boolean.FALSE;
                    EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
                    EObject obj = helper.resolveContainedElementAt(resource, caret);
                    for (EObject cur = obj; cur != null; cur = cur.eContainer())
                    {
                        String name = cur.eClass() != null ? cur.eClass().getName() : ""; //$NON-NLS-1$
                        if ("Invocation".equals(name) //$NON-NLS-1$
                            || "OperatorStyleCreator".equals(name)) //$NON-NLS-1$
                            return Boolean.TRUE;
                    }
                    return Boolean.FALSE;
                });
            return Boolean.TRUE.equals(ready);
        }
        catch (Exception ex)
        {
return false;
        }
    }

    /** Видимый shell подсказки параметров рядом с кареткой (probe по handler врёт). */
    private boolean isParamHoverShellVisible()
    {
        try
        {
            StyledText st = viewer != null ? viewer.getTextWidget() : null;
            if (st == null || st.isDisposed())
                return false;
            Display display = st.getDisplay();
            if (display == null || display.isDisposed())
                return false;
            Shell editorShell = st.getShell();
            Point caretDisp = st.toDisplay(st.getLocationAtOffset(st.getCaretOffset()));
            for (Shell shell : display.getShells())
            {
                if (shell == null || shell.isDisposed() || !shell.isVisible())
                    continue;
                if (shell == editorShell)
                    continue;
                Point size = shell.getSize();
                if (size.x < 50 || size.y < 20)
                    continue;
                Point loc = shell.getLocation();
                if (Math.abs(loc.x - caretDisp.x) > 600 || Math.abs(loc.y - caretDisp.y) > 600)
                    continue;
                if (hasBrowserDescendant(shell))
                    return true;
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    private static boolean hasBrowserDescendant(Control root)
    {
        if (root == null || root.isDisposed())
            return false;
        if (root.getClass().getName().contains("Browser")) //$NON-NLS-1$
            return true;
        if (root instanceof org.eclipse.swt.widgets.Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                if (hasBrowserDescendant(child))
                    return true;
            }
        }
        return false;
    }

    private void runInvocationParametersHoverCommand(int desiredCaret, int gen, int attempt)
    {
        if (gen != paramHintPostGen.get())
            return;
        StyledText st = viewer != null ? viewer.getTextWidget() : null;
        if (st == null || st.isDisposed())
            return;
        int caret = st.getCaretOffset();
        if (caret != desiredCaret)
        {
return;
        }
        if (isParamHoverShellVisible() || isParamHoverInfoControlVisible())
        {
            paramHintPostGen.incrementAndGet();
return;
        }
        ParamHoverProbe probe = new ParamHoverProbe();
        try
        {
            IWorkbenchPartSite site = facade != null ? facade.getSite() : null;
            if (site != null && site.getPage() != null)
            {
                if (bslEditor != null)
                    site.getPage().activate(bslEditor);
                else
                    site.getPage().activate(site.getPart());
            }
            if (!st.isFocusControl())
                st.setFocus();
            ParamHintHtmlModifier.ensureFirstActualArgTypesComputed();
            probe = executeInvocationParametersHoverCommand();
            if (!probe.popupShown && isParamHoverShellVisible())
                probe.popupShown = true;
            if (probe.popupShown)
                paramHintPostGen.incrementAndGet();
        }
        catch (Exception ex)
        {
            probe.execOk = false;
            probe.err = String.valueOf(ex);
        }
}

    /** Штатная команда BSL: Ctrl+Shift+Space — подсказка параметров вызова. */
    private static final String INVOCATION_PARAMETERS_HOVER_COMMAND =
        "com._1c.g5.v8.dt.bsl.ui.hover.InvocationParametersHover"; //$NON-NLS-1$

    private static final class ParamHoverProbe
    {
        boolean execOk;
        boolean popupShown;
        String handlerClass;
        String infoControlState;
        String err;
    }

    private ParamHoverProbe executeInvocationParametersHoverCommand()
    {
        ParamHoverProbe probe = new ParamHoverProbe();
        try
        {
            org.eclipse.ui.IWorkbenchWindow window =
                PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
            {
                probe.err = "windowNull"; //$NON-NLS-1$
                return probe;
            }
            org.eclipse.ui.IWorkbenchPartSite site = facade != null ? facade.getSite() : null;
            org.eclipse.ui.handlers.IHandlerService handlers = null;
            if (site != null)
                handlers = site.getService(org.eclipse.ui.handlers.IHandlerService.class);
            if (handlers == null)
                handlers = window.getService(org.eclipse.ui.handlers.IHandlerService.class);
            if (handlers == null)
            {
                probe.err = "handlersNull"; //$NON-NLS-1$
                return probe;
            }
            handlers.executeCommand(INVOCATION_PARAMETERS_HOVER_COMMAND, null);
            probe.execOk = true;
            fillParamHoverInfoControlProbe(probe);
            return probe;
        }
        catch (Exception ex)
        {
            probe.execOk = false;
            probe.err = String.valueOf(ex);
            return probe;
        }
    }

    private static boolean isParamHoverInfoControlVisible()
    {
        ParamHoverProbe probe = new ParamHoverProbe();
        fillParamHoverInfoControlProbe(probe);
        return probe.popupShown;
    }

    /**
     * {@code infoControl != null} только если EDT вызвал {@code showControlInfo}.
     * Реальный handler — через e4 {@code lookUpHandler} / ключ {@code handler::id}.
     */
    private static void fillParamHoverInfoControlProbe(ParamHoverProbe probe)
    {
        try
        {
            org.eclipse.core.commands.IHandler handler = resolveInvocationParametersHoverHandler();
            if (handler == null)
            {
                probe.infoControlState = "handlerNull"; //$NON-NLS-1$
                return;
            }
            probe.handlerClass = handler.getClass().getName();
            Field infoField = null;
            Class<?> cls = handler.getClass();
            while (cls != null && infoField == null)
            {
                try
                {
                    infoField = cls.getDeclaredField("infoControl"); //$NON-NLS-1$
                }
                catch (NoSuchFieldException ignored)
                {
                    cls = cls.getSuperclass();
                }
            }
            if (infoField == null)
            {
                probe.infoControlState = "fieldMissing"; //$NON-NLS-1$
                return;
            }
            infoField.setAccessible(true);
            Object infoControl = infoField.get(handler);
            if (infoControl == null)
            {
                probe.infoControlState = "null"; //$NON-NLS-1$
                probe.popupShown = false;
                return;
            }
            probe.infoControlState = infoControl.getClass().getSimpleName();
            probe.popupShown = true;
        }
        catch (Exception ex)
        {
            probe.infoControlState = "probeError:" + ex.getClass().getSimpleName(); //$NON-NLS-1$
            probe.err = String.valueOf(ex);
        }
    }

    private static org.eclipse.core.commands.IHandler resolveInvocationParametersHoverHandler()
    {
        try
        {
            Object context = null;
            org.eclipse.ui.IWorkbenchWindow window =
                PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null)
            {
                context = window.getService(Class.forName(
                    "org.eclipse.e4.core.contexts.IEclipseContext")); //$NON-NLS-1$
            }
            if (context == null && window != null && window.getActivePage() != null)
            {
                org.eclipse.ui.IWorkbenchPart part = window.getActivePage().getActivePart();
                if (part != null && part.getSite() != null)
                {
                    context = part.getSite().getService(Class.forName(
                        "org.eclipse.e4.core.contexts.IEclipseContext")); //$NON-NLS-1$
                }
            }
            if (context != null)
            {
                Class<?> impl = Class.forName(
                    "org.eclipse.e4.core.commands.internal.HandlerServiceImpl"); //$NON-NLS-1$
                java.lang.reflect.Method lookUp = impl.getMethod("lookUpHandler", //$NON-NLS-1$
                    Class.forName("org.eclipse.e4.core.contexts.IEclipseContext"), //$NON-NLS-1$
                    String.class);
                Object h = lookUp.invoke(null, context, INVOCATION_PARAMETERS_HOVER_COMMAND);
                if (h instanceof org.eclipse.core.commands.IHandler)
                    return (org.eclipse.core.commands.IHandler) h;
                java.lang.reflect.Method get =
                    context.getClass().getMethod("get", String.class); //$NON-NLS-1$
                Object direct = get.invoke(context,
                    "handler::" + INVOCATION_PARAMETERS_HOVER_COMMAND); //$NON-NLS-1$
                if (direct instanceof org.eclipse.core.commands.IHandler)
                    return (org.eclipse.core.commands.IHandler) direct;
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            ICommandService commands =
                PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands == null)
                return null;
            org.eclipse.core.commands.Command command =
                commands.getCommand(INVOCATION_PARAMETERS_HOVER_COMMAND);
            return command != null ? command.getHandler() : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * Снимок сразу после всех {@code documentChanged} (включая {@code DataEvent.doIt}),
     * до JFace {@code getSelection}/{@code setSelectedRange} в insertProposal.
     */
    private void schedulePostDoItLinkedModeDiag(IDocument doc, int insertOffset, String text)
    {
        if (!(doc instanceof IDocumentExtension ext) || completionAutoOpenDocumentListener == null)
            return;
        final String inserted = text;
        final int offset = insertOffset;
        try
        {
            ext.registerPostNotificationReplace(completionAutoOpenDocumentListener,
                (d, owner) -> logLinkedModeDiagPhase("postDoIt", d, offset, inserted)); //$NON-NLS-1$
        }
        catch (Exception ex)
        {
            ContentAssistDebug.debugModeLog("H-INSERT", "postDoItDiag", "registerError", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"ex\":\"" + ContentAssistDebug.jsonEscapeForLog(String.valueOf(ex)) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void logLinkedModeDiagPhase(String phase, IDocument doc, int insertOffset, String text)
    {
        try
        {
            StyledText st = viewer != null ? viewer.getTextWidget() : null;
            int caret = st != null && !st.isDisposed() ? st.getCaretOffset() : -1;
            boolean hasModel = doc != null && LinkedModeModel.hasInstalledModel(doc);
            boolean bslPresent = findBslDocumentListener(doc) != null;
            IDocumentListener bsl = findBslDocumentListener(doc);
            DataEventDiag mapDiag = bsl != null ? readDataEventDiag(bsl, text) : DataEventDiag.missing();
            int open = text != null ? text.indexOf('(') : -1;
            int desired = open >= 0 ? insertOffset + open + 1 : -1;
            int insertEnd = text != null ? insertOffset + text.length() : -1;
            ContentAssistDebug.debugModeLog("H-INSERT", "linkedModeDiag", phase, //$NON-NLS-1$ //$NON-NLS-2$
                "{\"caret\":" + caret //$NON-NLS-1$
                    + ",\"desired\":" + desired //$NON-NLS-1$
                    + ",\"insertEnd\":" + insertEnd //$NON-NLS-1$
                    + ",\"hasModel\":" + hasModel //$NON-NLS-1$
                    + ",\"bslPresent\":" + bslPresent //$NON-NLS-1$
                    + ",\"mapSize\":" + mapDiag.mapSize //$NON-NLS-1$
                    + ",\"hasKey\":" + mapDiag.hasKey //$NON-NLS-1$
                    + ",\"posStart\":" + mapDiag.posStart //$NON-NLS-1$
                    + ",\"length\":" + mapDiag.length //$NON-NLS-1$
                    + ",\"allInfo\":" + mapDiag.allInfoSize //$NON-NLS-1$
                    + ",\"objects\":" + mapDiag.objectsSize //$NON-NLS-1$
                    + ",\"text\":\"" + ContentAssistDebug.jsonEscapeForLog(
                        text != null && text.length() > 80 ? text.substring(0, 80) : text) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Наш listener часто раньше {@code BslDocumentListener} в списке — sync
     * {@code documentChanged} ещё до {@code doIt}. Проверяем re-add после всех listeners.
     */
    private void scheduleEnsureBslDocumentListenerAfterAssistInsert(DocumentEvent event)
    {
        if (pendingBslDocumentListener == null)
            return;
        if (event == null || event.getDocument() == null)
            return;
        if (!isAssistProposalInsertInProgress())
        {
            pendingBslDocumentListener = null;
            return;
        }
        final IDocument doc = event.getDocument();
        final IDocumentListener remembered = pendingBslDocumentListener;
        final String text = event.getText();
        final int offset = event.getOffset();
        StyledText widget = viewer != null ? viewer.getTextWidget() : null;
        Display display = widget != null && !widget.isDisposed() ? widget.getDisplay() : null;
        if (display == null || display.isDisposed())
        {
            ensureBslDocumentListenerReadded(doc, remembered, offset, text);
            return;
        }
        display.asyncExec(() -> ensureBslDocumentListenerReadded(doc, remembered, offset, text));
        display.timerExec(1, () -> logLinkedModeDiagPhase("async1", doc, offset, text)); //$NON-NLS-1$
        display.timerExec(10, () -> logLinkedModeDiagPhase("async10", doc, offset, text)); //$NON-NLS-1$
    }

    /**
     * Если {@code DataEvent.doIt} упал, штатный listener остаётся снятым с документа —
     * следующие вставки без LinkedMode. Вернуть его.
     */
    private void ensureBslDocumentListenerReadded(IDocument doc, IDocumentListener remembered,
        int insertOffset, String text)
    {
        if (remembered == null || doc == null)
            return;
        if (pendingBslDocumentListener == remembered)
            pendingBslDocumentListener = null;
        boolean present = findBslDocumentListener(doc) != null;
        boolean readded = false;
        if (!present)
        {
            try
            {
                doc.addDocumentListener(remembered);
                readded = true;
            }
            catch (Exception ex)
            {
                ContentAssistDebug.debugModeLog("H-INSERT", "ensureBslListener", "error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"ex\":\"" + ContentAssistDebug.jsonEscapeForLog(String.valueOf(ex)) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        boolean hasModel = LinkedModeModel.hasInstalledModel(doc);
        StyledText st = viewer != null ? viewer.getTextWidget() : null;
        int caret = st != null && !st.isDisposed() ? st.getCaretOffset() : -1;
        IDocumentListener bslNow = findBslDocumentListener(doc);
        DataEventDiag mapDiag = readDataEventDiag(bslNow != null ? bslNow : remembered, text);
logLinkedModeDiagPhase("async0", doc, insertOffset, text); //$NON-NLS-1$
        maybeShowParamHintAfterInsert(caret);
    }

    private boolean isAssistProposalInsertInProgress()
    {
        return suppressDocumentAutoOpenAfterSession
            || Boolean.TRUE.equals(SmartCompletionProposal.PROPOSAL_APPLY_IN_PROGRESS.get());
    }

    private static final String BSL_DOCUMENT_LISTENER_SIMPLE =
        "BslProposalProvider$BslDocumentListener"; //$NON-NLS-1$

    private static final class DataEventDiag
    {
        final boolean hasKey;
        final int mapSize;
        final int posStart;
        final int length;
        final int stopPos;
        final int allInfoSize;
        final int objectsSize;

        DataEventDiag(boolean hasKey, int mapSize, int posStart, int length, int stopPos,
            int allInfoSize, int objectsSize)
        {
            this.hasKey = hasKey;
            this.mapSize = mapSize;
            this.posStart = posStart;
            this.length = length;
            this.stopPos = stopPos;
            this.allInfoSize = allInfoSize;
            this.objectsSize = objectsSize;
        }

        static DataEventDiag missing()
        {
            return new DataEventDiag(false, -1, -1, -1, -1, -1, -1);
        }
    }

    private static DataEventDiag readDataEventDiag(IDocumentListener bslListener,
        String replacementText)
    {
        if (bslListener == null)
            return DataEventDiag.missing();
        try
        {
            Field mapField = bslListener.getClass().getDeclaredField("map"); //$NON-NLS-1$
            mapField.setAccessible(true);
            Object mapObj = mapField.get(bslListener);
            if (!(mapObj instanceof Map<?, ?> map))
                return DataEventDiag.missing();
            int mapSize = map.size();
            if (replacementText == null)
                return new DataEventDiag(false, mapSize, -1, -1, -1, -1, -1);
            Object dataEvent = map.get(replacementText);
            if (dataEvent == null)
                return new DataEventDiag(false, mapSize, -1, -1, -1, -1, -1);
            int posStart = readIntField(dataEvent, "posStart"); //$NON-NLS-1$
            int length = readIntField(dataEvent, "length"); //$NON-NLS-1$
            int stopPos = readIntField(dataEvent, "stopPos"); //$NON-NLS-1$
            int allInfo = readListSizeField(dataEvent, "allInfo"); //$NON-NLS-1$
            int objects = readListSizeField(dataEvent, "objects"); //$NON-NLS-1$
            return new DataEventDiag(true, mapSize, posStart, length, stopPos, allInfo, objects);
        }
        catch (Exception ex)
        {
            return DataEventDiag.missing();
        }
    }

    private static int readIntField(Object target, String name)
    {
        try
        {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(target);
            return v instanceof Integer i ? i.intValue() : -1;
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    private static int readListSizeField(Object target, String name)
    {
        try
        {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(target);
            if (v == null)
                return 0;
            if (v instanceof List<?> list)
                return list.size();
            return -1;
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    private static IDocumentListener findBslDocumentListener(IDocument doc)
    {
        if (doc == null)
            return null;
        for (IDocumentListener listener : listDocumentListeners(doc))
        {
            if (listener == null)
                continue;
            String name = listener.getClass().getName();
            if (name != null && name.endsWith(BSL_DOCUMENT_LISTENER_SIMPLE))
                return listener;
        }
        return null;
    }

    private static List<IDocumentListener> listDocumentListeners(IDocument doc)
    {
        List<IDocumentListener> result = new ArrayList<>();
        if (!(doc instanceof AbstractDocument))
            return result;
        try
        {
            Field field = AbstractDocument.class.getDeclaredField("fDocumentListeners"); //$NON-NLS-1$
            field.setAccessible(true);
            Object listObj = field.get(doc);
            if (listObj instanceof ListenerList<?> listenerList)
            {
                for (Object o : listenerList)
                {
                    if (o instanceof IDocumentListener l)
                        result.add(l);
                }
            }
            else if (listObj instanceof Iterable<?> iterable)
            {
                for (Object o : iterable)
                {
                    if (o instanceof IDocumentListener l)
                        result.add(l);
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return result;
    }

    /**
     * @return число удалённых objects без RSP; {@code -1} — DataEvent не найден / ошибка.
     */
    private static int sanitizeDataEventObjectsWithoutRsp(IDocumentListener bslListener,
        String replacementText)
    {
        if (bslListener == null || replacementText == null)
            return -1;
        try
        {
            Field mapField = bslListener.getClass().getDeclaredField("map"); //$NON-NLS-1$
            mapField.setAccessible(true);
            Object mapObj = mapField.get(bslListener);
            if (!(mapObj instanceof Map<?, ?> map))
                return -1;
            Object dataEvent = map.get(replacementText);
            if (dataEvent == null)
                return -1;
            Field objectsField = dataEvent.getClass().getDeclaredField("objects"); //$NON-NLS-1$
            objectsField.setAccessible(true);
            Object objectsObj = objectsField.get(dataEvent);
            if (!(objectsObj instanceof List<?> objects) || objects.isEmpty())
                return 0;
            List<Object> kept = new ArrayList<>(objects.size());
            int filtered = 0;
            StringBuilder objectsDiag = new StringBuilder("["); //$NON-NLS-1$
            int idx = 0;
            for (Object o : objects)
            {
                if (idx > 0)
                    objectsDiag.append(',');
                if (!(o instanceof EObject eObject))
                {
                    objectsDiag.append("{\"i\":").append(idx) //$NON-NLS-1$
                        .append(",\"type\":\"nonEObject\",\"class\":\"") //$NON-NLS-1$
                        .append(ContentAssistDebug.jsonEscapeForLog(
                            o != null ? o.getClass().getName() : "null")) //$NON-NLS-1$
                        .append("\"}"); //$NON-NLS-1$
                    kept.add(o);
                    idx++;
                    continue;
                }
                ObjectRspDiag rspDiag = diagnoseObjectRsp(eObject);
                boolean keep = shouldKeepDataEventObject(rspDiag);
                if (keep && !rspDiag.hasRsp
                    && "resourceNull".equals(rspDiag.reason) //$NON-NLS-1$
                    && hasPlatformMethodChain(rspDiag.containers))
                {
                    // ParamSet платформенного Method/Type: docs через getDocByParamSet без eResource.
                    rspDiag = new ObjectRspDiag(true, rspDiag.isProxy, rspDiag.resourceNull,
                        rspDiag.eClass, rspDiag.uri, "okPlatformChain", rspDiag.containers); //$NON-NLS-1$
                }
                objectsDiag.append(rspDiag.toJson(idx));
                if (keep)
                    kept.add(eObject);
                else
                    filtered++;
                idx++;
            }
            objectsDiag.append(']');
if (filtered == 0)
                return 0;
            objectsField.set(dataEvent, kept);
            return filtered;
        }
        catch (Exception ex)
        {
            ContentAssistDebug.debugModeLog("H-INSERT", "sanitizeDataEvent", "error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"ex\":\"" + ContentAssistDebug.jsonEscapeForLog(String.valueOf(ex)) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            return -1;
        }
    }

    /**
     * Оставляем object с RSP; {@code ParamSet} с цепочкой {@code Method}/{@code Type}
     * (платформенная модель без eResource — штатный {@code getDocByParamSet});
     * {@code FakeCtor} — сигнатура выбранного конструктора из proposal (без eResource,
     * штатный hover по objects; иначе Comfort открывает InvocationParametersHover по AST
     * и теряет выбранную перегрузку, см. «Новый Структура»).
     * Выкидываем сирот ({@code containers:none}) и прочие без RSP — иначе NPE в
     * {@code parseTemplateComment} валит {@code DataEvent.doIt} до {@code LinkedModeUI.enter}.
     */
    private static boolean shouldKeepDataEventObject(ObjectRspDiag diag)
    {
        if (diag == null)
            return false;
        if (diag.hasRsp)
            return true;
        if ("ParamSet".equals(diag.eClass) && hasPlatformMethodChain(diag.containers)) //$NON-NLS-1$
            return true;
        if ("FakeCtor".equals(diag.eClass)) //$NON-NLS-1$
            return true;
        return false;
    }

    private static boolean hasPlatformMethodChain(String containers)
    {
        if (containers == null || containers.isEmpty() || "none".equals(containers)) //$NON-NLS-1$
            return false;
        // Сегменты: "Method:resourceNull|ContextDef:…|Type:…"
        // Не путать с прикладным "BslContextDefMethod:rspNull@bm://…"
        // (contains("Method:") ложно срабатывал на BslContextDefMethod).
        String[] segments = containers.split("\\|"); //$NON-NLS-1$
        for (String segment : segments)
        {
            if (segment == null || segment.isEmpty())
                continue;
            int colon = segment.indexOf(':');
            String cls = colon >= 0 ? segment.substring(0, colon) : segment;
            if ("Method".equals(cls) || "Type".equals(cls)) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }
        return false;
    }

    private static final class ObjectRspDiag
    {
        final boolean hasRsp;
        final boolean isProxy;
        final boolean resourceNull;
        final String eClass;
        final String uri;
        final String reason;
        final String containers;

        ObjectRspDiag(boolean hasRsp, boolean isProxy, boolean resourceNull,
            String eClass, String uri, String reason)
        {
            this(hasRsp, isProxy, resourceNull, eClass, uri, reason, ""); //$NON-NLS-1$
        }

        ObjectRspDiag(boolean hasRsp, boolean isProxy, boolean resourceNull,
            String eClass, String uri, String reason, String containers)
        {
            this.hasRsp = hasRsp;
            this.isProxy = isProxy;
            this.resourceNull = resourceNull;
            this.eClass = eClass != null ? eClass : ""; //$NON-NLS-1$
            this.uri = uri != null ? uri : ""; //$NON-NLS-1$
            this.reason = reason != null ? reason : ""; //$NON-NLS-1$
            this.containers = containers != null ? containers : ""; //$NON-NLS-1$
        }

        String toJson(int index)
        {
            return "{\"i\":" + index //$NON-NLS-1$
                + ",\"hasRsp\":" + hasRsp //$NON-NLS-1$
                + ",\"proxy\":" + isProxy //$NON-NLS-1$
                + ",\"resourceNull\":" + resourceNull //$NON-NLS-1$
                + ",\"eClass\":\"" + ContentAssistDebug.jsonEscapeForLog(eClass) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"uri\":\"" + ContentAssistDebug.jsonEscapeForLog(uri) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"reason\":\"" + ContentAssistDebug.jsonEscapeForLog(reason) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"containers\":\"" + ContentAssistDebug.jsonEscapeForLog(containers) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static ObjectRspDiag diagnoseObjectRsp(EObject eObject)
    {
        if (eObject == null)
            return new ObjectRspDiag(false, false, true, "", "", "nullObject"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String eClass = eClassName(eObject);
        boolean proxy = isProxy(eObject);
        ObjectRspDiag self = diagnoseResourceRsp(eObject, eClass, proxy);
        if (self.hasRsp)
            return self;
        // ParamSet платформенных методов часто без eResource; EDT берёт docs через
        // eContainer → AbstractMethod (getDocByParamSet). Проверяем цепочку контейнеров.
        StringBuilder chain = new StringBuilder();
        try
        {
            int depth = 0;
            for (EObject cur = eObject.eContainer(); cur != null && depth < 8;
                cur = cur.eContainer(), depth++)
            {
                ObjectRspDiag via = diagnoseResourceRsp(cur, eClassName(cur), isProxy(cur));
                if (chain.length() > 0)
                    chain.append('|');
                chain.append(via.eClass).append(':').append(via.reason);
                if (!via.uri.isEmpty())
                    chain.append('@').append(shortUri(via.uri));
                if (via.hasRsp)
                {
                    return new ObjectRspDiag(true, proxy, self.resourceNull, eClass, via.uri,
                        "okViaContainer:" + via.eClass, chain.toString()); //$NON-NLS-1$
                }
            }
            if (chain.length() == 0)
                chain.append("none"); //$NON-NLS-1$
        }
        catch (Exception ex)
        {
            chain.append("ex:").append(ex.getClass().getSimpleName()); //$NON-NLS-1$
        }
        return new ObjectRspDiag(self.hasRsp, self.isProxy, self.resourceNull, self.eClass,
            self.uri, self.reason, chain.toString());
    }

    private static String shortUri(String uri)
    {
        if (uri == null || uri.isEmpty())
            return ""; //$NON-NLS-1$
        if (uri.length() <= 80)
            return uri;
        return uri.substring(0, 40) + "…" + uri.substring(uri.length() - 30); //$NON-NLS-1$
    }

    private static String eClassName(EObject eObject)
    {
        try
        {
            if (eObject != null && eObject.eClass() != null)
                return eObject.eClass().getName();
        }
        catch (Exception ignored)
        {
        }
        return ""; //$NON-NLS-1$
    }

    private static boolean isProxy(EObject eObject)
    {
        try
        {
            return eObject != null && eObject.eIsProxy();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static ObjectRspDiag diagnoseResourceRsp(EObject eObject, String eClass, boolean proxy)
    {
        Resource resource = null;
        try
        {
            resource = eObject.eResource();
        }
        catch (Exception ignored)
        {
        }
        if (resource == null)
            return new ObjectRspDiag(false, proxy, true, eClass, "", "resourceNull"); //$NON-NLS-1$ //$NON-NLS-2$
        URI uri = resource.getURI();
        String uriStr = uri != null ? uri.toString() : ""; //$NON-NLS-1$
        if (uri == null)
            return new ObjectRspDiag(false, proxy, false, eClass, "", "uriNull"); //$NON-NLS-1$ //$NON-NLS-2$
        IResourceServiceProvider rsp =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (rsp == null)
            return new ObjectRspDiag(false, proxy, false, eClass, uriStr, "rspNull"); //$NON-NLS-1$
        return new ObjectRspDiag(true, proxy, false, eClass, uriStr, "ok"); //$NON-NLS-1$
    }

    private void uninstallCompletionAutoOpenDocumentListener()
    {
        if (completionAutoOpenDocumentListener == null)
            return;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (doc != null)
            doc.removeDocumentListener(completionAutoOpenDocumentListener);
        completionAutoOpenDocumentListener = null;
    }

    private void logAutoOpenReadyDiagnostics()
    {
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        boolean autoOpenEnabled = settings != null && settings.isEnabled();
        boolean nativeEmpty = autoOpenEnabled && ComfortSettings.isReplaceListFiltersEnabled();
}

    private void onVerifyKeyForCompletionAutoOpen(VerifyEvent event)
    {
        onVerifyKeyForCompletionAutoOpenImpl(event);
    }

    private void onVerifyKeyForCompletionAutoOpenImpl(VerifyEvent event)
    {
        pendingAutoOpen = false;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        if (settings == null || !settings.isEnabled())
            return;
        char inserted = event.character == 0 ? 0 : (char)event.character;
        if (inserted == 0)
            return;
//        int startOffset = event.start; // У клавиатурного слушателя тут 0 всегда
        int startOffset = viewer.getTextWidget().getCaretOffset();
        int caretAfter = startOffset + 1;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        boolean popupWasOpen = ContentAssistPopupSync.isPopupVisible(assistant);
        String branch = CompletionAutoOpenTrigger.diagnoseFetch(
            doc, inserted, startOffset, caretAfter, popupWasOpen, event.stateMask);
        int seq = completionAutoOpenActiveSeq > 0
            ? completionAutoOpenActiveSeq : completionAutoOpenSeq.get();
if ((inserted == '(' || inserted == ',') && !popupWasOpen)
        {
            String linePrefix = BslAssistSourceHeuristics.linePrefixToCaret(
                doc, caretAfter, inserted, startOffset);
            if (!BslAssistSourceHeuristics.isInsideLineComment(linePrefix)
                && !BslAssistSourceHeuristics.isInsideStringLiteral(linePrefix))
            {
                pendingParamHintOnChar = true;
                pendingParamHintOnCharCaret = caretAfter;
            }
        }
    }

    /**
     * H-INSERT: таймлайн каретки после confirm — EDT LinkedMode в documentChanged
     * vs последующий JFace {@code getSelection}/{@code setSelectedRange}.
     * Отключено: шум и таймеры на каждый insert (вкл. вместе с NDJSON).
     */
    private void scheduleInsertCaretProbe(Display display, String reason)
    {
        // no-op
    }

    private void logInsertCaretProbe(String phase)
    {
        try
        {
            org.eclipse.swt.custom.StyledText text =
                viewer != null ? viewer.getTextWidget() : null;
            int caret = text != null && !text.isDisposed() ? text.getCaretOffset() : -1;
            String around = ""; //$NON-NLS-1$
            IDocument doc = viewer != null ? viewer.getDocument() : null;
            if (doc != null && caret >= 0)
            {
                int from = Math.max(0, caret - 16);
                int to = Math.min(doc.getLength(), caret + 16);
                around = doc.get(from, to - from);
            }
            ContentAssistDebug.debugModeLog("H-INSERT", "insertCaretProbe", phase, //$NON-NLS-1$ //$NON-NLS-2$
                "{\"caret\":" + caret //$NON-NLS-1$
                    + ",\"suppressAutoOpen\":" + suppressDocumentAutoOpenAfterSession //$NON-NLS-1$
                    + ",\"around\":\"" + ContentAssistDebug.jsonEscapeForLog(around) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception ignored)
        {
        }
    }

    private void logProposalDocumentInsert(DocumentEvent event)
    {
        if (event == null || event.getText() == null)
            return;
        String text = event.getText();
        if (text.indexOf("Вставить") < 0 && !(text.indexOf('(') >= 0 && text.indexOf(')') > text.indexOf('('))) //$NON-NLS-1$
            return;
        if (text.length() > 80)
            return;
        org.eclipse.swt.custom.StyledText widget =
            viewer != null ? viewer.getTextWidget() : null;
        int caret = widget != null && !widget.isDisposed() ? widget.getCaretOffset() : -1;
        ContentAssistDebug.debugModeLog("H-INSERT", "documentChanged", "proposalText", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"offset\":" + event.getOffset() //$NON-NLS-1$
                + ",\"length\":" + event.getLength() //$NON-NLS-1$
                + ",\"text\":\"" + ContentAssistDebug.jsonEscapeForLog(text) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"caretAfterEvent\":" + caret //$NON-NLS-1$
                + ",\"pendingAutoOpen\":" + pendingAutoOpen //$NON-NLS-1$
                + ",\"suppressAutoOpen\":" + suppressDocumentAutoOpenAfterSession //$NON-NLS-1$
                + ",\"proposalApplyFlag\":" //$NON-NLS-1$
                + Boolean.TRUE.equals(SmartCompletionProposal.PROPOSAL_APPLY_IN_PROGRESS.get())
                + "}"); //$NON-NLS-1$
        Display display = widget != null && !widget.isDisposed() ? widget.getDisplay() : null;
        scheduleInsertCaretProbe(display, "docChanged"); //$NON-NLS-1$
    }

    private void onDocumentChangedForCompletionAutoOpen(DocumentEvent event)
    {
        onDocumentChangedForCompletionAutoOpenImpl(event);
    }

    private void onDocumentChangedForCompletionAutoOpenImpl(DocumentEvent event)
    {
if (!pendingAutoOpen || !ComfortSettings.isReplaceListFiltersEnabled())
            return;
        pendingAutoOpen = false;
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        if (settings == null || !settings.isEnabled())
            return;
        if (event == null || event.getText() == null)
            return;
        String text = event.getText();
        if (text.isEmpty())
            return;
        char inserted = text.charAt(0);
        if (inserted == '\r' || inserted == '\n' || inserted == '\t')
            return;
        // Вставка proposal через SmartCompletionProposal.apply() — игнорируем.
        if (Boolean.TRUE.equals(SmartCompletionProposal.PROPOSAL_APPLY_IN_PROGRESS.get()))
            return;
        // Вставка необёрнутого proposal (пустой префикс — делегат не подключён).
        if (suppressDocumentAutoOpenAfterSession)
            return;
        int insertOffset = event.getOffset();
        int caretAfter;
        if ("\"\"".equals(text)) // вставлена пара двойных кавычек — каретка между ними
        {
            caretAfter = insertOffset + 1;
        }
        else
        {
            caretAfter = insertOffset + text.length();
        }
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        boolean popupWasOpen = ContentAssistPopupSync.isPopupVisible(assistant);
        String branch = CompletionAutoOpenTrigger.diagnoseFetch(
            doc, inserted, insertOffset, caretAfter, popupWasOpen, 0);
        int seq = completionAutoOpenSeq.incrementAndGet();
        completionAutoOpenActiveSeq = seq;
        completionAutoOpenAwaitingLogged = false;
        if (branch == null)
        {
return;
        }
if ("space".equals(branch) || "symbol".equals(branch))
        {
            // Пробел и &~#: не открываем EDT-попап, ИР решает через ЗаполнитьТаблицуСлов
            else            else
            {
                completionAutoOpenPending = true;
                completionAutoOpenCaret = caretAfter;
                completionAutoOpenIrScheduled = true;
                completionAutoOpenEdtOpened = false;
                completionAutoOpenActiveSeq = seq;
}
        }
        else
        {
            scheduleCompletionAutoOpen(caretAfter, seq);
        }
    }

    private void scheduleCompletionAutoOpen(int expectedCaretAfter, int autoOpenSeq)
    {
        int gen = completionAutoOpenScheduleGen.incrementAndGet();
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        int delay = settings != null ? settings.getTimeout() : 0;
        Control c = (Control)viewer.getTextWidget();
        if (c == null || c.isDisposed())
            return;
        Display display = c.getDisplay();
        if (display == null || display.isDisposed())
            return;
display.timerExec(delay, () -> fireCompletionAutoOpenTimer(expectedCaretAfter, autoOpenSeq, gen));
    }

    private void fireCompletionAutoOpenTimer(int expectedCaretAfter, int autoOpenSeq, int gen)
    {
        int genGlobal = completionAutoOpenScheduleGen.get();
        StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
        int liveCaret = text != null && !text.isDisposed() ? text.getCaretOffset() : -1;
        boolean popupVisible = ContentAssistPopupSync.isPopupVisible(assistant);
        if (gen != genGlobal)
        {
            logTimerAbort(autoOpenSeq, "genStale", gen, genGlobal, expectedCaretAfter, liveCaret); //$NON-NLS-1$
            return;
        }
        if (text == null || text.isDisposed())
        {
            logTimerAbort(autoOpenSeq, "widgetDisposed", gen, genGlobal, expectedCaretAfter, liveCaret); //$NON-NLS-1$
            return;
        }
        if (popupVisible)
        {
            logTimerAbort(autoOpenSeq, "popupVisible", gen, genGlobal, expectedCaretAfter, liveCaret); //$NON-NLS-1$
            return;
        }
        if (liveCaret < expectedCaretAfter)
        {
            logTimerAbort(autoOpenSeq, "caretMovedBack", gen, genGlobal, expectedCaretAfter, liveCaret); //$NON-NLS-1$
            return;
        }
        Display display = text.getDisplay();
        if (display == null || display.isDisposed())
        {
            logTimerAbort(autoOpenSeq, "displayDisposed", gen, genGlobal, expectedCaretAfter, liveCaret); //$NON-NLS-1$
            return;
        }
        display.asyncExec(() -> beginCompletionAutoOpen(liveCaret, autoOpenSeq));
    }

    private void logTimerAbort(int autoOpenSeq, String reason, int gen, int genGlobal,
                               int expectedCaret, int liveCaret)
    {
}

    private void beginCompletionAutoOpen(int caret, int autoOpenSeq)
    {
        completionAutoOpenActiveSeq = autoOpenSeq;
        if (caret < 0 || !ComfortSettings.isReplaceListFiltersEnabled())
        {
            logBeginEarlyReturn(autoOpenSeq, "badCaret", caret); //$NON-NLS-1$
            return;
        }
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        if (settings == null || !settings.isEnabled())
        {
            logBeginEarlyReturn(autoOpenSeq, "autoOpenOff", caret); //$NON-NLS-1$
            return;
        }
        if (ContentAssistPopupSync.isPopupVisible(assistant))
        {
            logBeginEarlyReturn(autoOpenSeq, "popupVisible", caret); //$NON-NLS-1$
            return;
        }
        completionAutoOpenPending = true;
        completionAutoOpenCaret = caret;
        completionAutoOpenEdtOpened = false;
        completionAutoOpenIrScheduled = false;
        completionAutoOpenAwaitingLogged = false;
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, false);
        processor.onAssistSessionContextReady(viewer, caret);
        IRSession session = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer);
        boolean irScheduled = false;
        if (session != null && !isWordsTableFetchInFlightForCaret(caret))
        {
            irScheduled = scheduleWordsTablePreparation(caret, true);
            if (irScheduled)
            {
                completionAutoOpenIrScheduled = true;
                // Ждём ИР: открытие только при autoOpenSuggested (ЗаполнитьТаблицуСлов).
                // Browser warmup — в openCompletionAutoIrPopup (preShowLiteralBrowserPatch).
return;
            }
        }
        warmupAssistBrowserCreator(caret);
        completionAutoOpenEdtOpened = true;
openCompletionAutoEdtPopup(caret, autoOpenSeq);
        if (!irScheduled)
            completionAutoOpenPending = false;
    }

    private void logBeginEarlyReturn(int autoOpenSeq, String reason, int caret)
    {
}

    private void openCompletionAutoEdtPopup(int expectedCaret, int autoOpenSeq)
    {
        SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, false); // false Заставляет штатную логику JFace вообще не открывать окно, если фильтрат пустой, но при этом вызывает звук Display.beep()
        beginLiteralOpenTracking();
        preShowLiteralBrowserPatch(expectedCaret);
        boolean shown = false;

        // From  org.eclipse.jface.text.contentassist.ContentAssistant.AutoAssistListener.showAssist(int)
        Object fAutoAssistListener = Global.getField(assistant, "fAutoAssistListener");
        Global.invoke(fAutoAssistListener, "start", 1); // учитывает задержку перед открытием из настроек плагина
        //
        //Global.invoke(assistant, "prepareToShowCompletions", true);
        //Object fProposalPopup = Global.getField(assistant, "fProposalPopup");
        //Global.invoke(fProposalPopup, "showProposals", true); // Не учитывает задержку перед открытием из настроек плагина
        //
        //shown = ContentAssistPopupSync.showPossibleCompletions(assistant); // Для штатного механизма это безусловное открытие окна как если бы CTRL+Space нажал. Поэтому выше продублировал штатную логику автооткрытия окна  
        
        IDtProject dtProject = facade.getDtProject();
        if (dtProject == null || !IRApplication.hasConnectedSessionForKeys(dtProject))
            return; // Иначе непустой список затирается пустым вероятно после фиктивного слияния с ИР 
        boolean popupVisible = shown && ContentAssistPopupSync.isPopupVisible(assistant);
        if (popupVisible && assistBrowserCreator == null)
        {
            IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(expectedCaret);
            if (fresh != null)
                rememberAssistBrowserCreator(fresh);
        }
        if (popupVisible && assistant != null && viewer != null && processor != null)
            syncLiteralPopupAfterShow(assistant, viewer, irCompletionSnapshot);
}

    private void openCompletionAutoIrPopup(IrBslCompletionSupport.Snapshot snapshot, int caret,
                                           int autoOpenSeq)
    {
        Control widget = (Control)viewer.getTextWidget();
        if (widget == null || widget.isDisposed())
            return;
        int liveCaret = widget instanceof StyledText st ? st.getCaretOffset() : caret;
        if (liveCaret < 0)
            liveCaret = caret;
        boolean inLiteral = SmartContentAssistProcessor.isStringLiteralAssistContext(
            viewer, liveCaret);
        boolean memberAccess = isMemberAccessAtCaret(liveCaret);
        // REGRESSION: на obj. с ИР нельзя открывать через irOnlyManualMode
        // (computeIrOnlyProposals → mergeIrForDisplay(EMPTY)): нет штатного
        // createProposals → нет DataEvent/LinkedMode → каретка в конце ();.
        // Ctrl+Space уже шёл через EDT+merge — auto должен совпадать (memberEdtFirst).
        if (memberAccess && !inLiteral)
        {
            openCompletionAutoMemberIrPopup(snapshot, liveCaret, autoOpenSeq);
            return;
        }
        processor.enterIrOnlyManualMode(liveCaret);
        processor.onAssistSessionContextReady(viewer, liveCaret);
        processor.primeIrSnapshotForDualAssist(snapshot);
        SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        beginLiteralOpenTracking();
        preShowLiteralBrowserPatch(liveCaret);
        boolean shown = ContentAssistPopupSync.showPossibleCompletions(assistant);
        boolean popupVisible = shown && ContentAssistPopupSync.isPopupVisible(assistant);
        if (popupVisible && assistBrowserCreator == null)
        {
            IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(liveCaret);
            if (fresh != null)
                rememberAssistBrowserCreator(fresh);
        }
        if (popupVisible && assistant != null && viewer != null && processor != null)
            syncLiteralPopupAfterShow(assistant, viewer, snapshot);
        // Вне литерала finishDeferredLiteralPopupSetup не вызывается — иначе setupPhase
        // блокирует requestIrPopupRefresh при смене контекста (напр. «.» после «_»).
        if (popupVisible && inLiteral)
        {
            widget.getDisplay().asyncExec(
                () -> finishDeferredLiteralPopupSetup(assistant, viewer));
        }
        else
        {
            setLiteralOpenSetupComplete(true);
            if (processor != null)
                processor.exitIrOnlyManualMode();
            if (pendingPopupRefresh)
                flushPendingPopupRefreshIfAny();
        }
        logOpenCompletionAutoIrPopup(liveCaret, shown, popupVisible, snapshot, autoOpenSeq,
            "irOnly"); //$NON-NLS-1$
    }

    /**
     * Автооткрытие на {@code obj.} с ИР: штатный EDT-список (LinkedMode) + primed ИР.
     *
     * <p><b>Не</b> вызывать {@link SmartContentAssistProcessor#enterIrOnlyManualMode} и
     * <b>не</b> {@link #syncLiteralPopupAfterShow} / flush-{@code recomputePopupList}:
     * повторный {@code createProposals} сбрасывает {@code DataEvent}. Схлопывание —
     * {@link ContentAssistPopupSync#scheduleSessionPopupSync} → in-place collapse.
     * Маршрут в логе: {@code route=memberEdtFirst}.
     */
    private void openCompletionAutoMemberIrPopup(IrBslCompletionSupport.Snapshot snapshot,
                                                 int liveCaret, int autoOpenSeq)
    {
        processor.exitIrOnlyManualMode();
        processor.onAssistSessionContextReady(viewer, liveCaret);
        processor.primeIrSnapshotForDualAssist(snapshot);
        SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        beginLiteralOpenTracking();
        preShowLiteralBrowserPatch(liveCaret);
        // Штатный show → EDT createProposals регистрирует DataEvent для LinkedMode.
        boolean shown = ContentAssistPopupSync.showPossibleCompletions(assistant);
        boolean popupVisible = shown && ContentAssistPopupSync.isPopupVisible(assistant);
        if (popupVisible && assistBrowserCreator == null)
        {
            IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(liveCaret);
            if (fresh != null)
                rememberAssistBrowserCreator(fresh);
        }
        setLiteralOpenSetupComplete(true);
        logOpenCompletionAutoIrPopup(liveCaret, shown, popupVisible, snapshot, autoOpenSeq,
            "memberEdtFirst"); //$NON-NLS-1$
    }

    private void logOpenCompletionAutoIrPopup(int liveCaret, boolean shown, boolean popupVisible,
                                              IrBslCompletionSupport.Snapshot snapshot,
                                              int autoOpenSeq, String route)
    {
        int irN = snapshot != null && snapshot.proposals != null ? snapshot.proposals.length : 0;
}

    private void clearCompletionAutoOpenState(String reason, int autoOpenSeq)
    {
        completionAutoOpenPending = false;
        completionAutoOpenCaret = -1;
        completionAutoOpenEdtOpened = false;
        completionAutoOpenIrScheduled = false;
        completionAutoOpenAwaitingLogged = false;
    }

    private void cancelCompletionAutoOpenTimers(int autoOpenSeq, String reason)
    {
        completionAutoOpenScheduleGen.incrementAndGet();
}

    private boolean isCompletionAutoOpenCaretMatch(int caret)
    {
        return completionAutoOpenCaret >= 0 && completionAutoOpenCaret == caret;
    }

    boolean isCompletionAutoOpenAwaitingWords()
    {
        if (!completionAutoOpenPending)
            return false;
        if (completionAutoOpenEdtOpened)
            return false;
        boolean inFlight = wordsTableFetchInFlight
            && wordsTableCaret == completionAutoOpenCaret;
        boolean result = inFlight;
        if (result)
            completionAutoOpenAwaitingLogged = true;
        return result;
    }

    int peekCompletionAutoOpenActiveSeq()
    {
        return completionAutoOpenActiveSeq;
    }

    private boolean isCompletionAutoOpenAwaitingIr()
    {
        if (!completionAutoOpenPending && !completionAutoOpenIrScheduled)
            return false;
        if (wordsTableFetchInFlight && wordsTableCaret == completionAutoOpenCaret)
            return true;
        return completionAutoOpenPending && !wordsTableReady
            && wordsTableCaret == completionAutoOpenCaret;
    }

    private boolean shouldIrAutoOpenPopup(IrBslCompletionSupport.Snapshot snapshot, int caret,
                                          boolean popupVisible)
    {
        String reject = null;
        if (snapshot == null || snapshot.proposals == null || snapshot.proposals.length == 0)
            reject = "noProposals"; //$NON-NLS-1$
        else if (!snapshot.autoOpenSuggested)
            reject = "autoOpenSuggestedFalse"; //$NON-NLS-1$
        else if (popupVisible)
            reject = "popupAlreadyVisible"; //$NON-NLS-1$
        else if (manualIrAssistPending)
            reject = "manualIrAssistPending"; //$NON-NLS-1$
        else
        {
            StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
            int liveCaret = text != null && !text.isDisposed() ? text.getCaretOffset() : -1;
            if (liveCaret != caret)
                reject = "caretMismatch:" + liveCaret + "!=" + caret; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (reject != null)
        {
return false;
        }
        return true;
    }

    private static boolean shouldOpenFromIr(IrBslCompletionSupport.Snapshot snapshot)
    {
        if (snapshot == null || snapshot.proposals == null)
            return false;
        return snapshot.proposals.length > 0 && snapshot.autoOpenSuggested;
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
if (!inLiteral)
            return;
        if (ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer) == null)
            return;
        if (caret < 0)
            return;
        tryBeginManualDualAssist(caret);
        event.doit = false;
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

    public TextEditorFacade getFacade()
    {
        return facade;
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
        {
            assistBrowserCreator = creator;
            BslCompletionSideHintResolver.rememberEditorBrowserCreator(bslEditor, creator);
        }
    }

    private void warmupAssistBrowserCreator(int caret)
    {
        if (assistBrowserCreator != null)
            return;
        IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(caret);
        if (fresh != null)
            rememberAssistBrowserCreator(fresh);
    }

    private void preShowLiteralBrowserPatch(int caret)
    {
        BslCompletionSideHintResolver.primeRspHoverCreator(viewer, caret, bslEditor);
        warmupAssistBrowserCreator(caret);
        if (assistBrowserCreator == null)
        {
            IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(caret);
            if (fresh != null)
                rememberAssistBrowserCreator(fresh);
        }
        if (assistBrowserCreator != null)
            ContentAssistPopupSync.patchAssistBrowserCreatorOnly(assistant, viewer);
        ContentAssistPopupSync.disposeStalePlainSidePanelIfNeeded(assistant);
    }

    /**
     * Cold literal-open: полная цепочка probe/editor/global/cold bootstrap (fix10f).
     */
    public IInformationControlCreator resolveAssistBrowserCreatorForLiteral()
    {
        int caret = SmartContentAssistProcessor.resolveSessionCaret(assistant, viewer);
        if (caret < 0 && manualIrAssistCaret >= 0)
            caret = manualIrAssistCaret;
        return resolveFreshAssistBrowserCreator(caret);
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

    public void removeIrActivation(String stableCacheKey)
    {
        if (stableCacheKey == null || stableCacheKey.isEmpty())
            return;
        irActivationByKey.remove(stableCacheKey);
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

    int getLiteralOpenGen()
    {
        return literalOpenGen;
    }

    /** H76: openGen для NDJSON вне экземпляра reloader. */
    public static int literalOpenGenForLog()
    {
        ContentAssistSessionReloader reloader = getActiveReloader();
        return reloader != null ? reloader.getLiteralOpenGen() : -1;
    }

    /** H76: ms с начала literal open. */
    public static long msSinceLiteralSessionStartForLog()
    {
        ContentAssistSessionReloader reloader = getActiveReloader();
        return reloader != null ? reloader.msSinceLiteralOpen() : -1;
    }

    /** Ожидаемое число ИР-строк в literal popup (H37 audit). */
    int getExpectedLiteralIrCount()
    {
        IrBslCompletionSupport.Snapshot snap = irCompletionSnapshot;
        if (snap != null && snap.proposals != null)
            return snap.proposals.length;
        return processor != null ? processor.resolvedIrProposalCount() : -1;
    }

    long msSinceLiteralOpen()
    {
        if (literalOpenStartedAtMs < 0)
            return -1;
        return System.currentTimeMillis() - literalOpenStartedAtMs;
    }

    boolean isLiteralBrowserPrimed()
    {
        return literalBrowserPrimed;
    }

    void setLiteralBrowserPrimed(boolean primed)
    {
        literalBrowserPrimed = primed;
    }

    boolean isLiteralFinishPinPending()
    {
        return literalFinishPinPending;
    }

    void setLiteralFinishPinPending(boolean pending)
    {
        literalFinishPinPending = pending;
    }

    /** {@code true} между show и завершением {@code finishDeferredLiteralPopupSetup} (fix10d). */
    boolean isLiteralOpenSetupPhase()
    {
        return literalOpenStartedAtMs >= 0 && !literalOpenSetupComplete;
    }

    void notifyIrMergedFullList(int mergedCount)
    {
        if (mergedCount <= 0)
            return;
        irMergeGeneration++;
        irMergeGenerationBumpPending = true;
    }

    int getIrMergeGeneration()
    {
        return irMergeGeneration;
    }

    boolean isPendingPopupRefresh()
    {
        return pendingPopupRefresh;
    }

    boolean isPendingIrPopupRefresh()
    {
        return pendingPopupRefresh && pendingPopupRefreshIsIr;
    }

    private void cancelLiteralFlushTimer()
    {
        literalFlushTimerGen.incrementAndGet();
        literalFlushTimerActive = false;
    }

    private void ensureLiteralFlushScheduledAfterGuard(long msSinceOpen)
    {
        if (literalFlushTimerActive)
            return;
        literalFlushTimerActive = true;
        logLiteralMergeTimeline("flushGuardDefer"); //$NON-NLS-1$
        int delay = (int) Math.max(1, LITERAL_OPEN_FLUSH_GUARD_MS - msSinceOpen);
        final int gen = literalFlushTimerGen.get();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            literalFlushTimerActive = false;
            return;
        }
        display.timerExec(delay, () -> {
            if (gen != literalFlushTimerGen.get())
                return;
            literalFlushTimerActive = false;
            flushPendingPopupRefreshIfAny();
        });
    }

    static void logLiteralMergeTimeline(String phase)
    {
        logLiteralMergeTimeline(phase, -1, false);
    }

    static void logLiteralMergeTimeline(String phase, int irN, boolean duplicate)
    {
        ContentAssistSessionReloader reloader = ACTIVE_RELOADER.get();
        long ms = reloader != null ? reloader.msSinceLiteralOpen() : -1L;
        int gen = reloader != null ? reloader.getLiteralOpenGen() : -1;
        boolean pending = reloader != null && reloader.isPendingPopupRefresh();
        int mergeGen = reloader != null ? reloader.getIrMergeGeneration() : -1;
        ContentAssistDebug.debugModeLog("H40", "literalMergeTimeline", phase, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"gen\":" + gen + ",\"msSinceOpen\":" + ms //$NON-NLS-1$
                + ",\"irN\":" + irN + ",\"duplicate\":" + duplicate //$NON-NLS-1$
                + ",\"pendingRefresh\":" + pending + ",\"irMergeGen\":" + mergeGen + "}"); //$NON-NLS-1$
    }

    boolean consumeIrMergeGenerationBump()
    {
        if (!irMergeGenerationBumpPending)
            return false;
        irMergeGenerationBumpPending = false;
        return true;
    }

    boolean isIrMergeGenerationBumpPending()
    {
        return irMergeGenerationBumpPending;
    }

    void setLiteralOpenSetupComplete(boolean complete)
    {
        literalOpenSetupComplete = complete;
    }

    int beginLiteralOpenTracking()
    {
        cancelLiteralFlushTimer();
        literalOpenGen++;
        literalOpenStartedAtMs = System.currentTimeMillis();
        literalBrowserPrimed = false;
        literalFinishPinPending = false;
        literalOpenSetupComplete = false;
        return literalOpenGen;
    }

    void logLiteralOpenPhase(ContentAssistant ca, int irN, String phase)
    {
        long ms = msSinceLiteralOpen();
        int tableRows = ContentAssistPopupSync.tableItemCountForAssistant(ca);
        boolean hasBrowser = ca != null
            && ContentAssistPopupSync.hasAssistBrowserSidePanel(ca);
}

    private void clearLiteralOpenState()
    {
        cancelLiteralFlushTimer();
        literalOpenStartedAtMs = -1;
        literalBrowserPrimed = false;
        literalFinishPinPending = false;
        literalOpenSetupComplete = false;
    }

    void logLiteralListState(ContentAssistant ca, int irN, String phase)
    {
        int tableRows = ContentAssistPopupSync.tableItemCountForAssistant(ca);
        boolean irResolved = processor != null && processor.isIrWordsResolvedForContext();
        boolean firstIr = ContentAssistPopupSync.firstProposalIsIr(ca);
        boolean listReady = irN > 0 && tableRows == irN && irResolved && firstIr;
}

    private void logLiteralOpenFlash(ContentAssistant ca, int irN, int tableRows,
        String recomputeReason)
    {
}

    private void reprimeLiteralSnapshot(IrBslCompletionSupport.Snapshot snapshot, SourceViewer sv)
    {
        if (snapshot == null || processor == null)
            return;
        processor.primeIrSnapshotForDualAssist(snapshot);
        if (sv != null)
        {
            IDocument doc = sv.getDocument();
            if (doc != null)
                processor.rememberIrSnapshotCache(snapshot, doc);
        }
    }

    private void syncLiteralPopupAfterShow(ContentAssistant ca, SourceViewer sv,
        IrBslCompletionSupport.Snapshot snapshot)
    {
        int irN = snapshot != null && snapshot.proposals != null ? snapshot.proposals.length : -1;
        logLiteralOpenPhase(ca, irN, "show"); //$NON-NLS-1$
        reprimeLiteralSnapshot(snapshot, sv);
        logLiteralListState(ca, irN, "afterReprime"); //$NON-NLS-1$
        auditLiteralList(ca, sv, irN, "afterReprime"); //$NON-NLS-1$
        boolean patched = ContentAssistPopupSync.patchAssistBrowserCreatorOnly(ca, sv);
        if (!patched)
            patched = ContentAssistPopupSync.patchAssistBrowserCreatorOnly(ca, sv);
        logLiteralOpenPhase(ca, irN, patched ? "patchBeforeRecompute" : "patchRetry"); //$NON-NLS-1$ //$NON-NLS-2$
        ContentAssistPopupSync.invalidateSyncSnapshot();
        int tableRows = ContentAssistPopupSync.tableItemCountForAssistant(ca);
        boolean irResolved = processor.isIrWordsResolvedForContext();
        boolean firstIr = ContentAssistPopupSync.firstProposalIsIr(ca);
        boolean snapshotReady = irN > 0 && irResolved && firstIr
            && snapshot != null && snapshot.proposals != null
            && snapshot.proposals.length == irN;
        boolean tableReady = irN > 0 && tableRows == irN;
        boolean listReady = tableReady && snapshotReady;
        if (snapshotReady && !tableReady)
        {
            logLiteralOpenFlash(ca, irN, tableRows, "snapshotReady"); //$NON-NLS-1$
            logLiteralOpenPhase(ca, irN, "skipRecompute"); //$NON-NLS-1$
        }
        else if (!listReady)
        {
            logLiteralOpenFlash(ca, irN, tableRows, "earlyRowCount"); //$NON-NLS-1$
            ContentAssistPopupSync.recomputePopupList(ca, sv, processor);
            logLiteralOpenPhase(ca, irN, "recompute"); //$NON-NLS-1$
        }
        else
        {
            logLiteralOpenFlash(ca, irN, tableRows, "listReady"); //$NON-NLS-1$
            logLiteralOpenPhase(ca, irN, "skipRecompute"); //$NON-NLS-1$
        }
        tableRows = ContentAssistPopupSync.tableItemCountForAssistant(ca);
        logLiteralListState(ca, irN, "afterList"); //$NON-NLS-1$
        auditLiteralList(ca, sv, irN, "afterList"); //$NON-NLS-1$
        if (tableRows == 0 && irN > 0)
        {
            auditLiteralList(ca, sv, irN, "recoveryBefore"); //$NON-NLS-1$
            scheduleEmptyListRecovery(ca, sv, snapshot, irN);
        }
    }

    private void auditLiteralList(ContentAssistant ca, SourceViewer sv, int irN, String phase)
    {
        ContentAssistPopupSync.auditLiteralPopupList(ca, sv, processor, facade, irN, phase);
    }

    private void scheduleEmptyListRecovery(ContentAssistant ca, SourceViewer sv,
        IrBslCompletionSupport.Snapshot snapshot, int irN)
    {
        Control widget = viewer != null ? (Control) viewer.getTextWidget() : null;
        if (widget == null || widget.isDisposed())
            return;
        Display display = widget.getDisplay();
        if (display == null || display.isDisposed())
            return;
        final int gen = literalOpenGen;
        display.asyncExec(() -> {
            if (gen != literalOpenGen || ca == null || sv == null || processor == null)
                return;
            if (!ContentAssistPopupSync.isPopupVisible(ca))
                return;
            if (ContentAssistPopupSync.tableItemCountForAssistant(ca) > 0)
                return;
            reprimeLiteralSnapshot(snapshot, sv);
            ContentAssistPopupSync.invalidateSyncSnapshot();
            ContentAssistPopupSync.recomputePopupList(ca, sv, processor);
            auditLiteralList(ca, sv, irN, "recoveryAfter"); //$NON-NLS-1$
});
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

    boolean isWordsTableFetchInFlightForContext(int caret)
    {
        return isWordsTableFetchInFlightForCaret(caret);
    }

    private boolean isMemberAccessAtCaret(int caret)
    {
        if (caret < 0 || viewer == null)
            return false;
        if (SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret))
            return false;
        IDocument doc = viewer.getDocument();
        return doc != null
            && SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(doc, caret) >= 0;
    }

    private void openMemberAssistRecoveryPopup(int caret)
    {
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        boolean shown = ContentAssistPopupSync.showPossibleCompletions(assistant);
        boolean popupVisible = shown && ContentAssistPopupSync.isPopupVisible(assistant);
        if (popupVisible && assistant != null && viewer != null && processor != null)
            ContentAssistPopupSync.recomputePopupList(assistant, viewer, processor);
}

    private void clearManualIrAssistState()
    {
        manualIrAssistPending = false;
        manualIrAssistCaret = -1;
        manualDualPopupOpened = false;
        irWordsResolvedForCaret = -1;
        clearLiteralOpenState();
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
return;
        }
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
return;
        }
        if (ContentAssistPopupSync.isPopupVisible(assistant))
        {
return;
        }
        IRSession session = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer);
        if (session == null)
        {
            IDtProject dtProject = facade != null ? facade.getDtProject() : null;
return;
        }
        manualIrAssistCaret = caret;
        manualIrAssistPending = true;
        manualDualPopupOpened = false;
        irWordsResolvedForCaret = -1;
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        warmupAssistBrowserCreator(caret);
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
IrCompletionDebug.log("manualDualAssist defer caret=" + caret); //$NON-NLS-1$
            return;
        }
        processor.enterIrOnlyManualMode(caret);
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
                caret, raw.contextType, raw.proposals, raw.wordsTransferred, raw.wordsDisplayed,
                raw.autoOpenSuggested);
        return new IrBslCompletionSupport.Snapshot(
            caret, "", new org.eclipse.jface.text.contentassist.ICompletionProposal[0], 0, 0, false); //$NON-NLS-1$
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
return;
        }
        SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        beginLiteralOpenTracking();
        IrBslCompletionSupport.Snapshot snap = irCompletionSnapshot;
        int irN = snap != null && snap.proposals != null ? snap.proposals.length : -1;
        preShowLiteralBrowserPatch(expectedCaret);
        boolean shown = ContentAssistPopupSync.showPossibleCompletions(assistant);
        manualDualPopupOpened = true;
        boolean popupVisible = shown && ContentAssistPopupSync.isPopupVisible(assistant);
        if (popupVisible && assistBrowserCreator == null)
        {
            int probeCaret = liveCaret >= 0 ? liveCaret : expectedCaret;
            IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(probeCaret);
            if (fresh != null)
                rememberAssistBrowserCreator(fresh);
        }
        if (popupVisible)
        {
            syncLiteralPopupAfterShow(assistant, viewer, snap);
            widget.getDisplay().asyncExec(() -> finishDeferredLiteralPopupSetup(assistant, viewer));
        }
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
        beginLiteralOpenTracking();
        preShowLiteralBrowserPatch(liveCaret);
        boolean shown = ContentAssistPopupSync.showPossibleCompletions(assistant);
        boolean popupVisible = shown && ContentAssistPopupSync.isPopupVisible(assistant);
        if (popupVisible && assistBrowserCreator == null)
        {
            IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(liveCaret);
            if (fresh != null)
                rememberAssistBrowserCreator(fresh);
        }
        if (popupVisible)
            manualDualPopupOpened = true;
        if (popupVisible && assistant != null && viewer != null && processor != null)
            syncLiteralPopupAfterShow(assistant, viewer, snapshot);
widget.getDisplay().asyncExec(() -> {
            if (assistant == null || viewer == null || processor == null)
                return;
            boolean stillVisible = ContentAssistPopupSync.isPopupVisible(assistant);
if (stillVisible)
            {
                finishDeferredLiteralPopupSetup(assistant, viewer);
            }
        });
    }

    /**
     * EDT уже показал popup до deferred open — adopt без повторного showPossibleCompletions (fix10j).
     */
    private void adoptEarlyVisibleLiteralPopup(IrBslCompletionSupport.Snapshot snapshot,
        int snapshotCaret)
    {
        Control widget = (Control) viewer.getTextWidget();
        if (widget == null || widget.isDisposed())
            return;
        int liveCaret = widget instanceof StyledText st ? st.getCaretOffset() : snapshotCaret;
        if (liveCaret < 0)
            liveCaret = snapshotCaret;
        boolean pendingBefore = manualIrAssistPending;
        boolean popupVisible = ContentAssistPopupSync.isPopupVisible(assistant);
        int irN = snapshot.proposals != null ? snapshot.proposals.length : 0;
processor.enterIrOnlyManualMode(liveCaret);
        processor.primeIrSnapshotForDualAssist(snapshot);
        SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
        ContentAssistPopupSync.ensureEmptyListAllowed(assistant, true);
        beginLiteralOpenTracking();
        preShowLiteralBrowserPatch(liveCaret);
        if (assistBrowserCreator == null)
        {
            IInformationControlCreator fresh = resolveFreshAssistBrowserCreator(liveCaret);
            if (fresh != null)
                rememberAssistBrowserCreator(fresh);
        }
        manualDualPopupOpened = true;
        syncLiteralPopupAfterShow(assistant, viewer, snapshot);
        processor.applyIrCompletion(snapshot, true);
        widget.getDisplay().asyncExec(() -> {
            if (assistant == null || viewer == null || processor == null)
                return;
            boolean stillVisible = ContentAssistPopupSync.isPopupVisible(assistant);
if (stillVisible)
                finishDeferredLiteralPopupSetup(assistant, viewer);
        });
    }

    /** Активация первой строки и pin side hint после deferred / manual open. */
    private void finishDeferredLiteralPopupSetup(ContentAssistant assistant, SourceViewer viewer)
    {
        if (assistant == null || viewer == null || processor == null)
            return;
        if (!ContentAssistPopupSync.isPopupVisible(assistant))
        {
            setLiteralOpenSetupComplete(true);
            return;
        }
        IrBslCompletionSupport.Snapshot snap = irCompletionSnapshot;
        int irN = snap != null && snap.proposals != null ? snap.proposals.length : -1;
        setLiteralFinishPinPending(true);
        boolean browserMigrateAttempted = false;
        try
        {
            boolean hasBrowser = ContentAssistPopupSync.forceLiteralSidePanelBrowserReady(
                assistant, viewer);
            browserMigrateAttempted = true;
            setLiteralBrowserPrimed(hasBrowser);
            boolean pinOk = ContentAssistPopupSync.pinIrSideHintForCurrentSelection(
                assistant, viewer);
            if (!pinOk && !ContentAssistPopupSync.hasAssistBrowserSidePanel(assistant))
                ContentAssistPopupSync.scheduleLiteralPinFallback(assistant, viewer);
            logLiteralOpenPhase(assistant, irN, "pinSideHint"); //$NON-NLS-1$
            ContentAssistPopupSync.syncLiteralRowSelectionWithoutPlainSelected(
                assistant, viewer);
            logLiteralOpenPhase(assistant, irN, "rowActivation"); //$NON-NLS-1$
            ContentAssistPopupSync.scheduleFilterBarSetup(assistant, viewer, processor);
            logLiteralOpenPhase(assistant, irN, "filterBar"); //$NON-NLS-1$
        }
        finally
        {
            setLiteralFinishPinPending(false);
        }
        logLiteralOpenPhase(assistant, irN, "finishDone"); //$NON-NLS-1$
        auditLiteralList(assistant, viewer, irN, "finishDone"); //$NON-NLS-1$
        logLiteralMergeTimeline("finishDone"); //$NON-NLS-1$
        setLiteralOpenSetupComplete(true);
        if (pendingPopupRefresh)
            flushPendingPopupRefreshIfAny();
        if (browserMigrateAttempted
            && !ContentAssistPopupSync.hasAssistBrowserSidePanel(assistant))
            ContentAssistPopupSync.finishLiteralBrowserVisualRefresh(assistant, viewer);
        manualIrAssistPending = false;
        processor.exitIrOnlyManualMode();
    }

    private IInformationControlCreator resolveFreshAssistBrowserCreator(int literalCaret)
    {
        BslCompletionSideHintResolver.primeRspHoverCreator(viewer, literalCaret, bslEditor);
        IInformationControlCreator fresh =
            assistBrowserCreator != null ? assistBrowserCreator : null;
        if (fresh == null)
            fresh = BslCompletionSideHintResolver.getEditorBrowserCreator(bslEditor);
        if (fresh == null)
            fresh = BslCompletionSideHintResolver.resolveAssistBrowserCreator(viewer);
        if (fresh == null)
            fresh = BslCompletionSideHintResolver.resolveAssistBrowserCreatorFromEditor(bslEditor);
        // probeDelegateBrowserCreator убран: он гонял computeCompletionProposals (полный
        // расчёт списка автодополнения) на UI-потоке только ради IInformationControlCreator.
        if (fresh == null)
            fresh = BslCompletionSideHintResolver
                .resolveAssistBrowserCreatorCold(bslEditor, viewer, processor).creator;
        if (fresh == null)
            fresh = BslCompletionSideHintResolver.resolveAssistBrowserCreator();
        return fresh;
    }

    private void scheduleWordsTablePreparation(int caret)
    {
        scheduleWordsTablePreparation(caret, false);
    }

    private boolean scheduleWordsTablePreparation(int caret, boolean autoInvoke)
    {
        if (facade == null || caret < 0)
            return false;
        IRSession session = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer);
        if (session == null)
        {
            IDtProject dtProject = facade.getDtProject();
return false;
        }
        if (isWordsTableFetchInFlightForCaret(caret))
        {
return false;
        }
        irWordsRequestStartedAtMs = System.currentTimeMillis();
        wordsTableReady = false;
        wordsTableCaret = caret;
        wordsTableFetchInFlight = true;
        irSnapshotAppliedCaret = -1;
        irSnapshotAppliedIrCount = -1;
        clearTransientIrSideHintState();
        irCompletionSnapshot = null;
        boolean closePrevious = irAssistCommandOpen;
        final int fetchDiagSeq = ++wordsTableFetchDiagSeq;
int syncCaretOffset = autoInvoke
            ? caret : IrBslCompletionSupport.SYNC_USE_EDITOR_SELECTION;
        IrBslCompletionSupport.prepareAssistContextAsync(session, facade, syncCaretOffset, autoInvoke,
            closePrevious, raw -> onWordsTablePrepared(caret, raw, fetchDiagSeq));
        return true;
    }

    private void onWordsTablePrepared(int caret, IrBslCompletionSupport.Snapshot raw, int fetchDiagSeq)
    {
        boolean staleFetch = fetchDiagSeq < wordsTableFetchDiagSeq;
        if (staleFetch)
        {
runPendingAfterWordsTable();
            return;
        }
        wordsTableFetchInFlight = false;
        wordsTableReady = true;
        wordsTableCaret = caret;
        irWordsResolvedForCaret = caret;
        IrBslCompletionSupport.Snapshot snapshot = buildSnapshotFromRaw(caret, raw);
        int irCount = snapshot.proposals != null ? snapshot.proposals.length : 0;
        int prevAppliedN = irSnapshotAppliedIrCount;
        int procIrN = processor.diagIrProposalCount();
        int procFullN = processor.diagFullListCacheCount();
        boolean popupNow = ContentAssistPopupSync.isPopupVisible(assistant);
        boolean wouldDowngradeApplied = prevAppliedN >= 0 && irCount < prevAppliedN;
        boolean wouldDowngradeProc = procIrN > irCount && processor.isIrWordsResolvedForContext();
        long sinceRequestMs = irWordsRequestStartedAtMs > 0
            ? System.currentTimeMillis() - irWordsRequestStartedAtMs : -1;
boolean duplicateSnapshot = irSnapshotAppliedCaret == caret
            && irSnapshotAppliedIrCount == irCount
            && processor.isIrWordsResolvedForContext();
        if (duplicateSnapshot)
        {
            logLiteralMergeTimeline("wordsTableDuplicate", irCount, true); //$NON-NLS-1$
runPendingAfterWordsTable();
            return;
        }
        logLiteralMergeTimeline("wordsTable", irCount, false); //$NON-NLS-1$
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
        if (shouldIrAutoOpenPopup(snapshot, caret, popupVisible))
        {
            int autoOpenSeq = completionAutoOpenActiveSeq;
openCompletionAutoIrPopup(snapshot, caret, autoOpenSeq);
            clearCompletionAutoOpenState("irAutoOpen", autoOpenSeq); //$NON-NLS-1$
            ContentAssistDebug.logAutoOpenDecision("irAutoOpen", caret, irCount); //$NON-NLS-1$
            irSnapshotAppliedCaret = caret;
            irSnapshotAppliedIrCount = irCount;
            runPendingAfterWordsTable();
            return;
        }
if (isCompletionAutoOpenCaretMatch(caret)
            && (completionAutoOpenPending || completionAutoOpenEdtOpened
                || completionAutoOpenIrScheduled))
        {
            int autoOpenSeq = completionAutoOpenActiveSeq;
            boolean irGate = shouldOpenFromIr(snapshot);
            boolean edtOpened = completionAutoOpenEdtOpened;
            String decision;
            if (!irGate && !edtOpened)
            {
                decision = "clearBothFail"; //$NON-NLS-1$
                clearCompletionAutoOpenState(decision, autoOpenSeq);
            }
            else if (edtOpened && popupVisible)
            {
                decision = "edtMerge"; //$NON-NLS-1$
                processor.applyIrCompletion(snapshot);
                if (assistant != null && viewer != null)
                    ContentAssistPopupSync.recomputePopupList(assistant, viewer, processor);
                clearCompletionAutoOpenState(decision, autoOpenSeq);
                setLiteralOpenSetupComplete(true);
            }
            else if (irGate && popupVisible)
            {
                decision = "irMerge"; //$NON-NLS-1$
                processor.applyIrCompletion(snapshot);
                if (assistant != null && viewer != null)
                    ContentAssistPopupSync.recomputePopupList(assistant, viewer, processor);
                clearCompletionAutoOpenState(decision, autoOpenSeq);
                setLiteralOpenSetupComplete(true);
            }
            else
            {
                decision = "noop"; //$NON-NLS-1$
                clearCompletionAutoOpenState(decision, autoOpenSeq);
            }
            ContentAssistDebug.logAutoOpenDecision(decision, caret, irCount);
            irSnapshotAppliedCaret = caret;
            irSnapshotAppliedIrCount = irCount;
            runPendingAfterWordsTable();
            return;
        }
        if (shouldOpenFromIr(snapshot) && !popupVisible && !manualIrAssistPending)
        {
            ContentAssistDebug.agentLog("H78", "onWordsTablePrepared", "autoOpenSkipped", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret //$NON-NLS-1$
                    + ",\"autoOpenSuggested\":" + snapshot.autoOpenSuggested //$NON-NLS-1$
                    + ",\"irN\":" + irCount + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (manualIrAssistPending && isManualCaretMatch(caret))
        {
            if (popupVisible)
            {
adoptEarlyVisibleLiteralPopup(snapshot, caret);
            }
            else
            {
openDeferredIrAssistPopup(snapshot, caret);
            }
        }
        else if (popupVisible)
        {
manualIrAssistPending = false;
            processor.applyIrCompletion(snapshot);
        }
        else
        {
processor.applyIrCompletion(snapshot);
            if (irCount > 0 && isMemberAccessAtCaret(caret) && !manualIrAssistPending)
                openMemberAssistRecoveryPopup(caret);
        }
        irSnapshotAppliedCaret = caret;
        irSnapshotAppliedIrCount = irCount;
        runPendingAfterWordsTable();
    }

    private void runPendingAfterWordsTable()
    {
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
        clearIrSelectionResetState(reloader);
        return reloader.preIrMergeSelectedRow <= 1;
    }

    private static void clearIrSelectionResetState(ContentAssistSessionReloader reloader)
    {
        if (reloader == null)
            return;
        reloader.preIrMergeSelectedRow = -1;
    }

    private void clearIrSelectionResetState()
    {
        clearIrSelectionResetState(this);
    }

    private void rememberPreIrMergeSelectionState(ContentAssistant assistant)
    {
        preIrMergeSelectedRow = ContentAssistPopupSync.readSelectedRowIndex(assistant);
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

    private static boolean shouldDeferLiteralMergeUntilBrowser(ContentAssistant ca,
        SourceViewer viewer)
    {
        if (ca == null || viewer == null)
            return false;
        int caret = SmartContentAssistProcessor.resolveSessionCaret(ca, viewer);
        if (!SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret))
            return false;
        return !ContentAssistPopupSync.hasAssistBrowserSidePanel(ca);
    }

    static void flushPendingPopupRefreshIfAny()
    {
        ContentAssistSessionReloader reloader = ACTIVE_RELOADER.get();
        if (reloader == null || !reloader.pendingPopupRefresh)
            return;
        SmartContentAssistProcessor processor = ACTIVE_PROCESSOR.get();
        if (reloader.pendingPopupRefreshIsIr)
        {
            long ms = reloader.msSinceLiteralOpen();
            if (ms >= 0 && ms < LITERAL_OPEN_FLUSH_GUARD_MS)
            {
                reloader.ensureLiteralFlushScheduledAfterGuard(ms);
                return;
            }
        }
        reloader.literalFlushTimerActive = false;
        boolean wasIr = reloader.pendingPopupRefreshIsIr;
        reloader.pendingPopupRefresh = false;
        reloader.pendingPopupRefreshIsIr = false;
        ContentAssistant ca = ACTIVE_ASSISTANT.get();
        SourceViewer viewer = ACTIVE_VIEWER.get();
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
            logLiteralMergeTimeline("flush"); //$NON-NLS-1$
            IrCompletionDebug.logIrPopupRefresh("flush"); //$NON-NLS-1$
            ContentAssistPopupSync.invalidateSyncSnapshot();
            reloader.rememberPreIrMergeSelectionState(ca);
        }
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
return;
        }
        if (irOnly && (reloader.isLiteralOpenSetupPhase()
            || reloader.isManualIrAssistPending()
            || shouldDeferLiteralMergeUntilBrowser(ca, viewer)))
        {
            reloader.pendingPopupRefresh = true;
            reloader.pendingPopupRefreshIsIr = true;
            logLiteralMergeTimeline("queuedSetup"); //$NON-NLS-1$
            IrCompletionDebug.logIrPopupRefresh("queuedSetup"); //$NON-NLS-1$
            return;
        }
        if (!ContentAssistPopupSync.isPopupVisible(ca))
        {
            if (irOnly)
                logLiteralMergeTimeline("skipNotVisible"); //$NON-NLS-1$
return;
        }
        if (ContentAssistPopupSync.isRecomputeInProgress())
        {
            reloader.pendingPopupRefresh = true;
            reloader.pendingPopupRefreshIsIr = irOnly;
            if (irOnly)
            {
                logLiteralMergeTimeline("queued"); //$NON-NLS-1$
                IrCompletionDebug.logIrPopupRefresh("queued"); //$NON-NLS-1$
            }
            return;
        }
        if (irOnly)
        {
            logLiteralMergeTimeline("now"); //$NON-NLS-1$
            IrCompletionDebug.logIrPopupRefresh("now"); //$NON-NLS-1$
            ContentAssistPopupSync.invalidateSyncSnapshot();
            reloader.rememberPreIrMergeSelectionState(ca);
        }
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
            {
                ContentAssistPopupSync.beginRecomputeTrigger("toggle"); //$NON-NLS-1$
                ContentAssistPopupSync.recomputePopupList(ca, viewer, processor);
            }
        });
    }

    /** literal repeat Ctrl+Space: compute без delegate (EDT nextMode). */
    public static boolean consumeLiteralRepeatFromCommand()
    {
        boolean hadFlag = Boolean.TRUE.equals(LITERAL_REPEAT_FROM_COMMAND.get());
        SmartContentAssistProcessor processor = ACTIVE_PROCESSOR.get();
if (!hadFlag)
            return false;
        LITERAL_REPEAT_FROM_COMMAND.remove();
        return true;
    }

    private static void installContentAssistCommandListener()
    {
        if (contentAssistCommandListenerRefs.getAndIncrement() > 0)
            return;
        try
        {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService == null)
                return;
            contentAssistCommandListener = new IExecutionListener() {
                @Override
                public void preExecute(String commandId, ExecutionEvent event)
                {
                    if (!CONTENT_ASSIST_PROPOSALS_COMMAND.equals(commandId))
                        return;
                    ContentAssistSessionReloader reloader = openSessionReloader;
                    if (reloader == null)
                        reloader = getActiveReloader();
                    if (reloader == null)
                        return;
                    reloader.onContentAssistProposalsCommand();
                }

                @Override
                public void notHandled(String commandId, NotHandledException exception)
                {
                }

                @Override
                public void postExecuteFailure(String commandId, ExecutionException exception)
                {
                    if (!CONTENT_ASSIST_PROPOSALS_COMMAND.equals(commandId))
                        return;
                    logContentAssistCommandPost("failure", null, exception); //$NON-NLS-1$
                }

                @Override
                public void postExecuteSuccess(String commandId, Object returnValue)
                {
                    if (!CONTENT_ASSIST_PROPOSALS_COMMAND.equals(commandId))
                        return;
                    logContentAssistCommandPost("success", returnValue, null); //$NON-NLS-1$
                }
            };
            commandService.addExecutionListener(contentAssistCommandListener);
        }
        catch (Exception ignored) {}
    }

    private static void uninstallContentAssistCommandListener()
    {
        if (contentAssistCommandListenerRefs.decrementAndGet() > 0)
            return;
        try
        {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService != null && contentAssistCommandListener != null)
                commandService.removeExecutionListener(contentAssistCommandListener);
        }
        catch (Exception ignored) {}
        contentAssistCommandListener = null;
    }

    private void onContentAssistProposalsCommand()
    {
        boolean popupVisible = ContentAssistPopupSync.isPopupVisible(assistant);
        int caret = ContentAssistPopupSync.syncSessionOffsets(assistant, viewer);
        boolean inLiteral = caret >= 0
            && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        boolean irConnected = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(
            facade, viewer) != null;
if (!popupVisible)
            return;
        tryLiteralRepeatFilterToggle("command"); //$NON-NLS-1$
    }

    /** Checkbox-path toggle «Фильтр» для literal repeat (не member, не EDT nextMode). */
    private boolean tryLiteralRepeatFilterToggle(String source)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        if (!ContentAssistPopupSync.isPopupVisible(assistant))
            return false;
        int caret = ContentAssistPopupSync.syncSessionOffsets(assistant, viewer);
        if (caret < 0
            || !SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret))
            return false;
        boolean irConnected = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(
            facade, viewer) != null;
        int tableRowsBefore = ContentAssistPopupSync.tableItemCountForAssistant(assistant);
LITERAL_REPEAT_FROM_COMMAND.set(Boolean.TRUE);
        lastLiteralToggleMs = System.currentTimeMillis();
        ContentAssistPopupSync.seedStableDelegateFromVisiblePopup(assistant, processor);
        ContentAssistPopupSync.captureSelectionBeforeFilterToggle(assistant);
        SmartAssistFilterState.toggle();
scheduleFilterToggleUiSync();
        return true;
    }

    private static void logContentAssistCommandPost(String outcome, Object returnValue,
                                                    ExecutionException exception)
    {
        ContentAssistant ca = ACTIVE_ASSISTANT.get();
        SourceViewer viewer = ACTIVE_VIEWER.get();
        int caret = ca != null && viewer != null
            ? ContentAssistPopupSync.syncSessionOffsets(ca, viewer) : -1;
        boolean inLiteral = caret >= 0 && viewer != null
            && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        ContentAssistSessionReloader reloader = getActiveReloader();
        boolean irConnected = reloader != null && viewer != null
            && IrBslExpressionHtmlSupport.resolveIrSessionForAssist(
                reloader.getFacade(), viewer) != null;
        String returnClass = returnValue != null ? returnValue.getClass().getSimpleName() : "null"; //$NON-NLS-1$
        String err = exception != null && exception.getMessage() != null
            ? ContentAssistDebug.jsonEscapeForLog(exception.getMessage()) : ""; //$NON-NLS-1$
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

    /** {@link ICompletionListener} + {@link ICompletionListenerExtension} на одном объекте для JFace. */
    private abstract static class CompletionListenerAdapter
        implements ICompletionListener, ICompletionListenerExtension
    {
        @Override
        public void assistSessionRestarted(ContentAssistEvent event)
        {
        }
    }

    /** Ctrl+Space: при закрытом popup — запрос ИР; при открытом — блок EDT (toggle в compute, как member). */
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
            boolean popupVisible = ContentAssistPopupSync.isPopupVisible(assistant);
            boolean inLiteralProbe = probeCaret >= 0
                && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, probeCaret);
if (popupVisible)
            {
event.doit = false;
                return;
            }
            if (probeCaret >= 0
                && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, probeCaret))
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                if (IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer) == null)
                    return;
                if (text == null || text.isDisposed())
                    return;
                int caret = text.getCaretOffset();
                if (caret < 0)
                    return;
beginManualDualAssist(caret);
                event.doit = false;
return;
            }
if (!ComfortSettings.isReplaceListFiltersEnabled())
            {
return;
            }
            if (IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer) == null)
            {
return;
            }
            if (text == null || text.isDisposed())
            {
return;
            }
            int caret = text.getCaretOffset();
            if (caret < 0)
            {
return;
            }
beginManualDualAssist(caret);
            event.doit = false;
}
    }
}
