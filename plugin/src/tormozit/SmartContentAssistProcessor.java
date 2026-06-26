package tormozit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;
import org.eclipse.swt.graphics.Point;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.mcore.TypeItem;

/**
 * Обёртка над {@link IContentAssistProcessor}.
 *
 * <p>Загружает полный список у delegate и оборачивает в {@link SmartCompletionProposal}.
 * Фильтрация — {@link #filterAndSort} по кэшу; при вводе debounced {@code recomputePopupList}.
 * Полный список delegate грузится в фоне ({@link #scheduleLoadFullListCache}).
 */
public class SmartContentAssistProcessor implements IContentAssistProcessor
{
    private static final int NAME_WEIGHT = 10;
    private static final int PARAM_WEIGHT = 1;
    private static final ICompletionProposal[] EMPTY = new ICompletionProposal[0];
    /** Кэш member-access с большим списком — не дёргать delegate повторно. */
    private static final int MIN_STABLE_MEMBER_CACHE = 20;

    private static final ThreadLocal<Integer> LAST_COMPUTE_CARET = new ThreadLocal<>();

    private final IContentAssistProcessor delegate;
    private final String activationChars;

    private ICompletionProposal[] fullListCache = EMPTY;
    /** Порядок элементов в {@link #fullListCache} для tie-break при равном рейтинге. */
    private IdentityHashMap<ICompletionProposal, Integer> delegateOrderMap;
    /** Последний алфавитный список при выключенном smart-фильтре. */
    private ICompletionProposal[] lastStableEmptyList = EMPTY;
    /** Последний список в порядке delegate при smart-фильтре и пустом префиксе. */
    private ICompletionProposal[] lastStableDelegateList = EMPTY;
    private Runnable pendingEagerLoadTask;
    private boolean fullListReady = false;
    /** Полный список delegate загружен; иначе только interim с каретки. */
    private boolean fullListComplete = false;
    /** Позиция '.' контекста member-access для кэша, {@code Integer.MIN_VALUE} — без точки. */
    private int fullListContextKey = Integer.MIN_VALUE;
    /** Отмена устаревших {@link #scheduleMemberAccessReload}. */
    private int memberAccessReloadSeq = 0;
    /** Уже запланирована догрузка для текущего {@link #memberAccessReloadSeq}. */
    private int memberAccessReloadScheduledSeq = -1;
    /** Полный штатный список member-access у точки (без префикса идентификатора). */
    private ICompletionProposal[] memberStockFullList = EMPTY;
    /** Позиция {@code '.'} для {@link #memberStockFullList}. */
    private int memberStockFullListDot = -1;
    /** Пауза без ввода и с пустым фильтром перед тяжёлой {@link #loadFullList}. */
    private static final int IDLE_FULL_LIST_MS = 1500;
    private Runnable pendingIdleLoadTask;
    private boolean pendingIdleLoadReadOnly;
    /** Префикс на прошлом шаге фильтра (тот же anchor, смена «сооб»→«мета»). */
    private String lastTrackedFilter = ""; //$NON-NLS-1$
    /** Member-access: приёмник без свойств (Число и т.п.) — пустой список окончательный. */
    private boolean terminalEmptyMemberAccess;
    /** Позиция {@code '.'} для {@link #terminalEmptyMemberAccess}. */
    private int cachedNonObjectDot = -1;
    /** Sync {@link #fetchDelegateList} уже выполнен для {@link #fullListContextKey}. */
    private int delegateSyncProbedContextKey = Integer.MIN_VALUE;
    /** Слова ИР для текущего контекста assist (после {@link #applyIrCompletion}). */
    private ICompletionProposal[] irProposals = EMPTY;
    private int irSnapshotContextKey = Integer.MIN_VALUE;
    /** Штатный список delegate без слов ИР. */
    private ICompletionProposal[] delegateListCache = EMPTY;
    private int irSnapshotCacheContextKey = Integer.MIN_VALUE;
    private long irSnapshotCacheDocStamp = -1L;
    private IrBslCompletionSupport.Snapshot irSnapshotCache;
    /** ИР ответил для текущего контекста (в т.ч. пустым списком). */
    private boolean irWordsResolved;
    /** Ручной Ctrl+Space — popup только со словами ИР (literal и др.). */
    private boolean irOnlyManualMode;

    public SmartContentAssistProcessor(IContentAssistProcessor delegate, String activationChars)
    {
        this.delegate = delegate;
        this.activationChars = activationChars;
    }

    public IContentAssistProcessor getDelegate()
    {
        return delegate;
    }

    /**
     * Извлекает тип ресивера из кэша списка автодополнения.
     *
     * <p>1С:EDT формирует displayString элементов как:
     * {@code <Имя> : <ТипЗначения> ~ <ТипРодителя>}
     * Часть после {@code ~} — это расчётный тип выражения слева от точки.
     * Собираем уникальные значения и возвращаем {@code "(N) Тип"}.
     *
     * @return метка вида {@code "(3) ОбъектМетаданных"} или {@code ""} если кэш пуст / нет метаданных о типе
     */
    public String resolveReceiverTypeLabel()
    {
        java.util.LinkedHashSet<String> types = collectParentTypesFromProposals(fullListCache);
        if (types.isEmpty())
            types = collectParentTypesFromProposals(memberStockFullList);
        if (types.isEmpty() && !SmartAssistFilterState.isSmartFilterEnabled())
            types = collectParentTypesFromProposals(lastStableEmptyList);
        if (types.isEmpty())
            types = collectParentTypesFromProposals(lastStableDelegateList);

        if (types.isEmpty())
            return ""; //$NON-NLS-1$

        // Если все элементы одного типа — показываем просто тип
        if (types.size() == 1)
            return types.iterator().next();

        // Несколько типов — счётчик + первый (основной) тип
        return "(" + types.size() + ") " + types.iterator().next(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static java.util.LinkedHashSet<String> collectParentTypesFromProposals(
            ICompletionProposal[] proposals)
    {
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
        if (proposals == null || proposals.length == 0)
            return types;
        for (ICompletionProposal p : proposals)
        {
            String display = displayString(unwrapProposal(p));
            if (display == null)
                continue;
            String parentType = extractParentType(display);
            if (parentType != null && !parentType.isEmpty())
                types.add(parentType);
        }
        return types;
    }

    /**
     * Извлекает часть после {@code ~} в displayString элемента автодополнения.
     * Формат: {@code Имя : ТипЗначения ~ ТипРодителя}
     */
    static String extractParentType(String displayString)
    {
        if (displayString == null)
            return null;
        int tilde = displayString.indexOf('~');
        if (tilde < 0)
            return null;
        String after = displayString.substring(tilde + 1).trim();
        return after.isEmpty() ? null : after;
    }

    public void invalidateCache()
    {
        fullListReady = false;
        fullListComplete = false;
        assignFullListCache(EMPTY);
        lastStableEmptyList = EMPTY;
        lastStableDelegateList = EMPTY;
        fullListContextKey = Integer.MIN_VALUE;
        memberAccessReloadSeq++;
        memberAccessReloadScheduledSeq = -1;
        resetMemberStockFullList();
        cancelIdleFullListLoad();
        pendingEagerLoadTask = null;
        lastTrackedFilter = ""; //$NON-NLS-1$
        terminalEmptyMemberAccess = false;
        cachedNonObjectDot = -1;
        clearDelegateSyncProbe();
        irProposals = EMPTY;
        irSnapshotContextKey = Integer.MIN_VALUE;
        delegateListCache = EMPTY;
        irWordsResolved = false;
        ContentAssistDebug.log("invalidateCache"); //$NON-NLS-1$
    }

    void primeIrSnapshotForDualAssist(IrBslCompletionSupport.Snapshot snapshot)
    {
        applyIrSnapshotData(snapshot);
        applyStockAssistImagesFromDelegate(irProposals, delegateListCache);
    }

    void enterIrOnlyManualMode(int caret)
    {
        irOnlyManualMode = true;
        if (caret >= 0)
            LAST_COMPUTE_CARET.set(caret);
    }

    void exitIrOnlyManualMode()
    {
        irOnlyManualMode = false;
    }

    boolean isIrOnlyManualMode()
    {
        return irOnlyManualMode;
    }

    /** @deprecated use {@link #enterIrOnlyManualMode} */
    void enterLiteralManualIrMode(int caret)
    {
        enterIrOnlyManualMode(caret);
    }

    /** @deprecated use {@link #exitIrOnlyManualMode} */
    void exitLiteralManualIrMode()
    {
        exitIrOnlyManualMode();
    }

    /** @deprecated use {@link #isIrOnlyManualMode} */
    boolean isLiteralManualIrMode()
    {
        return isIrOnlyManualMode();
    }

    /** IR-only список для отложенного ручного assist (literal и др.). */
    private ICompletionProposal[] computeIrOnlyProposals(ITextViewer viewer, int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret, false);
        rebindIrSnapshotContext();
        String filter = SmartFilterTracker.getCurrentFilter();
        if (!isIrWordsResolvedForContext())
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H4", "computeIrOnlyProposals", "pending", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + ",\"ctxKey\":" + fullListContextKey //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"irCtxKey\":" + irSnapshotContextKey + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return EMPTY;
        }
        ICompletionProposal[] merged = mergeIrForDisplay(EMPTY);
        // #region agent log
        ContentAssistDebug.debugModeLog("H4", "computeIrOnlyProposals", "ctx", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + caret + ",\"resolved\":" + isIrWordsResolvedForContext() //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"ctxKey\":" + fullListContextKey + ",\"irCtxKey\":" + irSnapshotContextKey //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"irN\":" + irProposals.length + ",\"filter\":\"" + filter + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
        if (merged.length == 0)
            return EMPTY;
        ICompletionProposal[] result = applyLiteralSmartFilter(merged, filter);
        auditLiteralComputeReturn(viewer, resolveEditorForLiteralAudit(viewer),
            result.length, "computeReturn", null); //$NON-NLS-1$
        return result;
    }

    boolean isIrWordsResolvedForContext()
    {
        return irWordsResolved && irSnapshotContextKey == fullListContextKey;
    }

    /** Число ИР-предложений в текущем контексте (H37 audit). */
    int resolvedIrProposalCount()
    {
        return isIrWordsResolvedForContext() ? irProposals.length : 0;
    }

    /** Есть ИР-слово с тем же dedup-ключом (overlap ИР+EDT в литерале). */
    boolean hasIrProposalForDedupKey(String key)
    {
        if (!hasIrProposalsForCurrentContext() || key == null || key.isEmpty())
            return false;
        for (ICompletionProposal p : irProposals)
        {
            if (key.equalsIgnoreCase(dedupKey(p)))
                return true;
        }
        return false;
    }

    private void applyIrSnapshotData(IrBslCompletionSupport.Snapshot snapshot)
    {
        if (snapshot == null || snapshot.proposals == null)
            irProposals = EMPTY;
        else
            irProposals = snapshot.proposals;
        irWordsResolved = true;
        irSnapshotContextKey = fullListContextKey;
    }

    /**
     * Применяет snapshot слов ИР и запрашивает обновление popup (сразу или в очередь).
     */
    public void applyIrCompletion(IrBslCompletionSupport.Snapshot snapshot)
    {
        applyIrCompletion(snapshot, true);
    }

    /**
     * @param scheduleRefresh {@code false} — только данные snapshot; refresh отложит вызывающий код
     */
    public void applyIrCompletion(IrBslCompletionSupport.Snapshot snapshot,
        boolean scheduleRefresh)
    {
        if (snapshot == null)
            return;
        int beforeIrN = irProposals.length;
        int beforeFullN = fullListCache.length;
        int incomingIrN = snapshot.proposals != null ? snapshot.proposals.length : 0;
        applyIrSnapshotData(snapshot);
        applyStockAssistImagesFromDelegate(irProposals, delegateListCache);
        if (irProposals.length == 0)
        {
            IrCompletionDebug.logApplyIrCompletion(0, "empty"); //$NON-NLS-1$
            if (scheduleRefresh)
                ContentAssistSessionReloader.requestIrPopupRefresh();
            return;
        }
        // #region agent log
        debugSessionOrderSample("H4", "applyIrCompletion", "irSnapshot", irProposals, 12); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
        String baseKind = "none"; //$NON-NLS-1$
        if (delegateListCache.length > 0)
        {
            rebuildMergedFullListCache();
            fullListReady = true;
            baseKind = "delegate"; //$NON-NLS-1$
        }
        else
        {
            ICompletionProposal[] interim = unwrapStableDelegateBase();
            if (interim.length > 0)
            {
                fullListCache = mergeIrProposals(interim);
                rebuildDelegateOrderMap();
                fullListReady = true;
                baseKind = "interim"; //$NON-NLS-1$
            }
            else if (irProposals.length > 0)
            {
                fullListCache = mergeIrProposals(EMPTY);
                rebuildDelegateOrderMap();
                fullListReady = true;
                baseKind = "irOnly"; //$NON-NLS-1$
                clearDelegateSyncProbe();
            }
        }
        IrCompletionDebug.logApplyIrCompletion(irProposals.length, baseKind);
        // #region agent log
        ContentAssistDebug.debugModeLog("H50", "applyIrCompletion", "irApplied", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"irN\":" + irProposals.length //$NON-NLS-1$
                + ",\"baseKind\":\"" + baseKind //$NON-NLS-1$
                + "\",\"fullListReady\":" + fullListReady //$NON-NLS-1$
                + ",\"fullListN\":" + fullListCache.length //$NON-NLS-1$
                + ",\"delegateCacheN\":" + delegateListCache.length //$NON-NLS-1$
                + ",\"interimN\":" + unwrapStableDelegateBase().length //$NON-NLS-1$
                + ",\"delegateProbed\":" + isDelegateSyncProbedForContext() //$NON-NLS-1$
                + ",\"ctxKey\":" + fullListContextKey //$NON-NLS-1$
                + ",\"irCtxKey\":" + irSnapshotContextKey + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (beforeIrN > irProposals.length || (beforeFullN > fullListCache.length && beforeFullN > 0))
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H54", "applyIrCompletion", "applyDowngrade", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"beforeIrN\":" + beforeIrN + ",\"afterIrN\":" + irProposals.length //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"incomingIrN\":" + incomingIrN //$NON-NLS-1$
                    + ",\"beforeFullN\":" + beforeFullN + ",\"afterFullN\":" + fullListCache.length //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"baseKind\":\"" + baseKind //$NON-NLS-1$
                    + "\",\"ctxKey\":" + fullListContextKey //$NON-NLS-1$
                    + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
        }
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null && fullListReady && fullListCache.length > irProposals.length)
            reloader.notifyIrMergedFullList(fullListCache.length);
        if (scheduleRefresh)
            ContentAssistSessionReloader.requestIrPopupRefresh();
    }

    boolean hasIrProposalsForCurrentContext()
    {
        return irProposals.length > 0 && irSnapshotContextKey == fullListContextKey;
    }

    boolean shouldRefreshIrPopup()
    {
        return hasIrProposalsForCurrentContext() || isIrWordsResolvedForContext();
    }

    /** H54: текущее число IR-предложений в processor (только диагностика). */
    int diagIrProposalCount()
    {
        return irProposals.length;
    }

    /** H54: размер fullListCache (только диагностика). */
    int diagFullListCacheCount()
    {
        return fullListCache.length;
    }

    /** H66/H70: размер stable delegate cache. */
    int literalStableDelegateCount()
    {
        return lastStableDelegateList.length;
    }

    /** H66: snapshot кэшей для NDJSON. */
    String literalCacheSnapshotJson()
    {
        return "{\"stableN\":" + lastStableDelegateList.length //$NON-NLS-1$
            + ",\"delegateCacheN\":" + delegateListCache.length //$NON-NLS-1$
            + ",\"fullListN\":" + fullListCache.length + "}"; //$NON-NLS-1$
    }

    /** Заполнить stable/delegate cache из видимого popup (literal без ИР). */
    void absorbStableFromPopupProposals(ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return;
        rememberInterimDelegateList(raw);
        if (hasIrProposalsForCurrentContext() || isIrWordsResolvedForContext())
            return;
        ICompletionProposal[] stock = stripIrProposals(unwrapProposals(raw));
        if (stock.length > 0)
        {
            assignFullListCache(stock);
            fullListReady = true;
            markDelegateSyncProbed();
        }
        // #region agent log
        ContentAssistDebug.debugModeLog("H70", "absorbStableFromPopup", "seed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"rawN\":" + raw.length //$NON-NLS-1$
                + ",\"stableN\":" + lastStableDelegateList.length //$NON-NLS-1$
                + ",\"delegateCacheN\":" + delegateListCache.length //$NON-NLS-1$
                + ",\"fullListN\":" + fullListCache.length //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    void rebindIrSnapshotContext()
    {
        if (irWordsResolved)
            irSnapshotContextKey = fullListContextKey;
    }

    /** После {@link #invalidateCache} и {@link #ensureFullListForContext} — мгновенный snapshot из кэша. */
    void tryRestoreIrSnapshotFromCache(IDocument doc, int caret)
    {
        if (irSnapshotCache == null || doc == null || caret < 0)
        {
            IrCompletionDebug.logSnapshotCache(false, fullListContextKey, -1L);
            return;
        }
        long stamp = doc instanceof IDocumentExtension4 ext4
            ? ext4.getModificationStamp() : -1L;
        if (fullListContextKey != irSnapshotCacheContextKey || stamp != irSnapshotCacheDocStamp)
        {
            IrCompletionDebug.logSnapshotCache(false, fullListContextKey, stamp);
            return;
        }
        IrCompletionDebug.logSnapshotCache(true, fullListContextKey, stamp);
        applyIrCompletion(irSnapshotCache);
    }

    void rememberIrSnapshotCache(IrBslCompletionSupport.Snapshot snapshot, IDocument doc)
    {
        if (snapshot == null)
            return;
        irSnapshotCache = new IrBslCompletionSupport.Snapshot(
            snapshot.caret, snapshot.contextType, snapshot.proposals,
            snapshot.wordsTransferred, snapshot.wordsDisplayed);
        irSnapshotCacheContextKey = fullListContextKey;
        irSnapshotCacheDocStamp = doc instanceof IDocumentExtension4 ext4
            ? ext4.getModificationStamp() : -1L;
    }

    void onAssistSessionContextReady(ITextViewer viewer, int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret, false);
        tryRestoreIrSnapshotFromCache(doc, caret);
    }

    private ICompletionProposal[] unwrapStableDelegateBase()
    {
        if (lastStableDelegateList.length > 0)
            return stripIrProposals(unwrapProposals(lastStableDelegateList));
        if (memberStockFullList.length > 0)
            return stripIrProposals(unwrapProposals(memberStockFullList));
        return EMPTY;
    }

    /**
     * Merge ИР в delegate для popup.
     */
    ICompletionProposal[] mergeIrForDisplay(ICompletionProposal[] delegateBase)
    {
        if (!isIrWordsResolvedForContext() && irProposals.length == 0)
            return delegateBase != null ? delegateBase : EMPTY;
        if (irSnapshotContextKey != fullListContextKey)
            return delegateBase != null ? delegateBase : EMPTY;
        if (delegateBase == null || delegateBase.length == 0)
        {
            if (isIrWordsResolvedForContext() && irProposals.length > 0)
                return mergeIrProposals(EMPTY);
            return EMPTY;
        }
        if (irProposals.length == 0)
            return delegateBase;
        return mergeIrProposals(delegateBase);
    }

    private ICompletionProposal[] popupListWithIr(ICompletionProposal[] base)
    {
        ICompletionProposal[] merged = mergeIrForDisplay(unwrapProposals(base != null ? base : EMPTY));
        if (merged.length == 0)
            return EMPTY;
        ICompletionProposal[] result = buildDelegateOrderedList(merged);
        // #region agent log
        debugSessionOrderSample("H3", "popupListWithIr", "popup", result, 12); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        MemberEdtPriorityProfile popupProfile = isIrAssistOrderingEnabled()
            ? MemberEdtPriorityProfile.analyze(unwrapProposals(base != null ? base : EMPTY))
            : MemberEdtPriorityProfile.NONE;
        debugIrMergeOrderSample("H3", "popupListWithIr", "popupIr", result, //$NON-NLS-1$ //$NON-NLS-2$
            hasIrProposalsForCurrentContext() ? buildIrPriorityByKey(irProposals) : null,
            popupProfile);
        // #endregion
        return result;
    }

    static void clearLastComputeCaret()
    {
        LAST_COMPUTE_CARET.remove();
    }

    /** Только каретка и префикс фильтра (debounced recompute, без idle load). */
    static void primeFilterTrackerOnly(ITextViewer viewer, int caret)
    {
        int widgetCaret = resolveWidgetCaret(viewer);
        if (widgetCaret >= 0)
            caret = widgetCaret;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        String previous = processor != null
            ? processor.lastTrackedFilter
            : SmartFilterTracker.getCurrentFilter();
        updateFilterTracker(viewer, caret);
        if (processor != null)
        {
            processor.onFilterPrefixChanged(viewer, caret, previous,
                SmartFilterTracker.getCurrentFilter());
            processor.onFilterActivity(viewer);
        }
    }

    /** Каретка, фильтр и сброс контекста кэша (debounced recompute popup). */
    void primeAssistContextForCaret(ITextViewer viewer, int caret)
    {
        int widgetCaret = resolveWidgetCaret(viewer);
        if (widgetCaret >= 0)
            caret = widgetCaret;
        if (isStringLiteralAssistContext(viewer, caret))
            return;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret, isPopupVisible());
    }

    /** Каретка, фильтр и планирование idle-загрузки кэша (compute / старт сессии). */
    static void primeAssistContext(ITextViewer viewer, int caret)
    {
        int widgetCaret = resolveWidgetCaret(viewer);
        if (widgetCaret >= 0)
            caret = widgetCaret;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        String previous = processor != null
            ? processor.lastTrackedFilter
            : SmartFilterTracker.getCurrentFilter();
        updateFilterTracker(viewer, caret);
        if (isStringLiteralAssistContext(viewer, caret))
            return;
        if (processor != null)
        {
            processor.onFilterPrefixChanged(viewer, caret, previous,
                SmartFilterTracker.getCurrentFilter());
            processor.scheduleEagerFullListLoad(viewer);
            processor.rescheduleIdleFullListLoad(viewer, false);
        }
    }

    /** Каретка в строковом литерале BSL — штатный assist EDT без smart-фильтра Комфорт. */
    static boolean isStringLiteralAssistContext(ITextViewer viewer, int caret)
    {
        return isStringLiteralAssistContext(viewer != null ? viewer.getDocument() : null, caret);
    }

    static boolean isStringLiteralAssistContext(IDocument doc, int caret)
    {
        boolean ixtext = doc instanceof IXtextDocument;
        boolean inLiteral = false;
        if (ixtext && caret >= 0)
            inLiteral = EditEmbeddedTextHandler.isCaretInStringLiteral((IXtextDocument) doc, caret);
        if (ixtext && caret >= 0)
            return inLiteral;
        return false;
    }

    /** H4: детали определения literal-контекста для debug-c201d2.log. */
    static void debugLiteralContext(ITextViewer viewer, int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        boolean ixtext = doc instanceof IXtextDocument;
        boolean inLiteral = isStringLiteralAssistContext(doc, caret);
        boolean extractOk = ixtext && caret >= 0
            && EditEmbeddedTextHandler.isCaretInStringLiteral((IXtextDocument) doc, caret);
        // #region agent log
        ContentAssistDebug.debugModeLog("H4", "isStringLiteralAssistContext", "probe", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + caret + ",\"ixtext\":" + ixtext //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"inLiteral\":" + inLiteral + ",\"extractOk\":" + extractOk + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private static void updateFilterTracker(ITextViewer viewer, int caret)
    {
        if (caret < 0)
            return;
        LAST_COMPUTE_CARET.set(caret);
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        SmartFilterTracker.setCurrentFilter(computeIdentifierFilter(doc, caret));
    }

    public boolean isFullListReady()
    {
        return fullListReady;
    }

    public boolean isFullListComplete()
    {
        return fullListComplete;
    }

    /** Отмена idle-загрузки при вводе; полная загрузка — только при пустом фильтре. */
    public void onFilterActivity(ITextViewer viewer)
    {
        cancelIdleFullListLoad();
        if (!fullListComplete && viewer != null
            && SmartFilterTracker.getCurrentFilter().isEmpty())
            rescheduleIdleFullListLoad(viewer, false);
    }

    private static boolean isPopupVisible()
    {
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        return assistant != null && ContentAssistPopupSync.isPopupVisible(assistant);
    }

    private void markTerminalEmptyMemberAccess(int dot)
    {
        assignFullListCache(EMPTY);
        fullListReady = true;
        fullListComplete = true;
        terminalEmptyMemberAccess = true;
        if (dot >= 0)
            cachedNonObjectDot = dot;
        memberAccessReloadScheduledSeq = memberAccessReloadSeq;
        ContentAssistDebug.log("memberAccess terminal empty dot=" + dot); //$NON-NLS-1$
    }

    private boolean shouldScheduleMemberReload(int dot)
    {
        if (terminalEmptyMemberAccess)
            return false;
        if (dot >= 0 && cachedNonObjectDot == dot)
            return false;
        return true;
    }

    /**
     * @return {@link #EMPTY} если приёмник non-object; {@code null} — продолжать обычный путь
     */
    private ICompletionProposal[] tryTerminalNonObjectMemberAccess(ITextViewer viewer,
        IDocument doc, int caret)
    {
        if (doc == null || caret < 0)
            return null;
        if (isStringLiteralAssistContext(doc, caret))
            return null;
        int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
        if (dot < 0)
            return null;
        if (terminalEmptyMemberAccess && cachedNonObjectDot == dot)
            return EMPTY;
        if (!ReceiverTypeLabel.isNonObjectReceiver(doc, dot, caret))
            return null;
        markTerminalEmptyMemberAccess(dot);
        return EMPTY;
    }

    /**
     * Без видимого popup: один delegate или фильтр по кэшу; без sync member-capture и reload.
     */
    private ICompletionProposal[] computeProposalsLight(ITextViewer viewer, int offset)
    {
        int caret = resolveInvocationCaret(viewer, offset);
        if (isStringLiteralAssistContext(viewer, caret))
            return delegate.computeCompletionProposals(viewer, offset);
        primeFilterTrackerOnly(viewer, caret);
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret, false);

        ICompletionProposal[] terminal = tryTerminalNonObjectMemberAccess(viewer, doc, caret);
        if (terminal != null)
            return terminal;
        if (terminalEmptyMemberAccess)
            return EMPTY;

        int probeOffset = resolveDelegateProbeOffset(viewer, offset, caret);
        String filter = SmartFilterTracker.getCurrentFilter();
        return resolveProposalList(viewer, probeOffset, caret, filter,
            SmartAssistFilterState.isSmartFilterEnabled());
    }

    private static void applyStockAssistImagesFromDelegate(
        ICompletionProposal[] irList, ICompletionProposal[] delegateList)
    {
        if (irList == null || irList.length == 0)
            return;
        java.util.Map<String, org.eclipse.swt.graphics.Image> images = new java.util.HashMap<>();
        if (delegateList != null)
        {
            for (ICompletionProposal p : delegateList)
            {
                String key = dedupKey(p);
                if (key.isEmpty() || images.containsKey(key))
                    continue;
                org.eclipse.swt.graphics.Image img = unwrapProposal(p).getImage();
                if (img != null && !img.isDisposed())
                    images.put(key, img);
            }
        }
        for (ICompletionProposal p : irList)
        {
            ICompletionProposal raw = unwrapProposal(p);
            if (!(raw instanceof IrCompletionProposal ir))
                continue;
            org.eclipse.swt.graphics.Image img = images.get(dedupKey(p));
            if (img != null)
                ir.applyStockAssistImage(img);
        }
    }

    /**
     * Быстрый пересчёт popup по тёплому кэшу; {@code null} — нужен полный {@link #computeForPopupRefresh}.
     */
    ICompletionProposal[] filterCachedProposalsForPopup(ITextViewer viewer, int caret, String filter)
    {
        if (isStringLiteralAssistContext(viewer, caret)
            && !irOnlyManualMode
            && !hasIrProposalsForCurrentContext()
            && !shouldRefreshIrPopup())
        {
            ICompletionProposal[] stock = resolveStockLiteralProposalSource(viewer, caret);
            if (stock.length > 0)
            {
                ICompletionProposal[] result;
                if (!SmartAssistFilterState.isSmartFilterEnabled()
                    || filter == null || filter.isEmpty())
                    result = stock;
                else
                    result = filterAndSort(stock, filter);
                IDocument doc = viewer != null ? viewer.getDocument() : null;
                debugFilterCachedExit(viewer, caret, filter, doc, stock.length, "stockStable", //$NON-NLS-1$
                    result);
                return result;
            }
            return null;
        }
        if (!SmartAssistFilterState.isSmartFilterEnabled())
            return null;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (isCacheValidForCaret(doc, caret))
        {
            ICompletionProposal[] result = filter == null || filter.isEmpty()
                ? finalizeListForIrAssistDisplay(fullListCache)
                : filterAndSort(fullListCache, filter);
            debugFilterCachedExit(viewer, caret, filter, doc, fullListCache.length, "cache", result); //$NON-NLS-1$
            return result;
        }
        ICompletionProposal[] interim = interimSourceForCaret(doc, caret);
        if (interim != null)
        {
            ICompletionProposal[] merged = mergeIrForDisplay(unwrapProposals(interim));
            ICompletionProposal[] result = filter == null || filter.isEmpty()
                ? finalizeListForIrAssistDisplay(merged)
                : filterAndSort(merged, filter);
            debugFilterCachedExit(viewer, caret, filter, doc, interim.length, "interim", result); //$NON-NLS-1$
            return result;
        }
        if (isDelegateSyncProbedForContext())
        {
            ICompletionProposal[] result;
            String path;
            if (hasIrProposalsForCurrentContext())
            {
                ICompletionProposal[] merged = mergeIrForDisplay(EMPTY);
                result = filter == null || filter.isEmpty()
                    ? finalizeListForIrAssistDisplay(merged)
                    : filterAndSort(merged, filter);
                path = "delegateProbedIr"; //$NON-NLS-1$
            }
            else
            {
                result = filter == null || filter.isEmpty()
                    ? EMPTY
                    : filterAndSort(EMPTY, filter);
                path = "delegateProbed"; //$NON-NLS-1$
            }
            debugFilterCachedExit(viewer, caret, filter, doc, 0, path, result);
            return result;
        }
        return null;
    }

    private ICompletionProposal[] resolveStockLiteralProposalSource(ITextViewer viewer, int caret)
    {
        ICompletionProposal[] stable = unwrapStableDelegateBase();
        if (stable.length > 0)
            return stable;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ICompletionProposal[] interim = interimSourceForCaret(doc, caret);
        if (interim != null && interim.length > 0)
            return stripIrProposals(unwrapProposals(interim));
        if (delegateListCache.length > 0 && isDelegateSyncProbedForContext())
            return stripIrProposals(unwrapProposals(delegateListCache));
        return EMPTY;
    }

    private void markDelegateSyncProbed()
    {
        delegateSyncProbedContextKey = fullListContextKey;
        // #region agent log
        String filter = SmartFilterTracker.getCurrentFilter();
        ContentAssistDebug.debugModeLog("H48a", "markDelegateSyncProbed", "delegateProbedSet", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"ctxKey\":" + fullListContextKey //$NON-NLS-1$
                + ",\"filter\":\"" + ContentAssistDebug.jsonEscapeForLog(filter != null ? filter : "") //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"irN\":" + irProposals.length //$NON-NLS-1$
                + ",\"irResolved\":" + isIrWordsResolvedForContext() + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private void clearDelegateSyncProbe()
    {
        delegateSyncProbedContextKey = Integer.MIN_VALUE;
    }

    private boolean isDelegateSyncProbedForContext()
    {
        return delegateSyncProbedContextKey == fullListContextKey;
    }

    /**
     * Сброс interim-кэша, если префикс заменён (сооб→пусто→мета), а не дописан.
     * Якорь тот же — {@link #computeFullListContextKey} не меняется, delegate-список устаревает.
     */
    private void onFilterPrefixChanged(ITextViewer viewer, int caret, String previous,
                                       String next)
    {
        if (previous == null)
            previous = ""; //$NON-NLS-1$
        if (next == null)
            next = ""; //$NON-NLS-1$

        IDocument doc = viewer != null ? viewer.getDocument() : null;
        String docFilter = computeIdentifierFilter(doc, caret);
        if (!next.equals(docFilter))
        {
            next = docFilter;
            SmartFilterTracker.setCurrentFilter(next);
        }
        previous = lastTrackedFilter;

        int dot = doc != null && caret >= 0
            ? ReceiverTypeLabel.findMemberAccessDot(doc, caret) : -1;
        if (!previous.isEmpty() && next.isEmpty() && dot >= 0)
            handleMemberAccessTransition(viewer, caret, dot);

        if (!shouldInvalidateInterimCache(previous, next))
        {
            lastTrackedFilter = next;
            return;
        }
        invalidateInterimListCache(viewer);
        lastTrackedFilter = next;
    }

    /**
     * Сброс interim-кэша: очистка слова или смена первого символа (сооб→мета).
     * Не по «ни один не префикс другого» — даёт ложные срабатывания при гонках каретки.
     */
    private static boolean shouldInvalidateInterimCache(String previous, String next)
    {
        if (next.isEmpty() && !previous.isEmpty())
            return true;
        if (!next.isEmpty() && !previous.isEmpty()
            && Character.toLowerCase(next.charAt(0))
                != Character.toLowerCase(previous.charAt(0)))
            return true;
        return false;
    }

    private void handleMemberAccessTransition(ITextViewer viewer, int caret, int dot)
    {
        SmartFilterTracker.setCurrentFilter(""); //$NON-NLS-1$
        lastTrackedFilter = ""; //$NON-NLS-1$
        lastStableEmptyList = EMPTY;
        lastStableDelegateList = EMPTY;
        fullListReady = false;
        fullListComplete = false;
        assignFullListCache(EMPTY);
        resetMemberStockFullList();
        memberAccessReloadSeq++;
        memberAccessReloadScheduledSeq = -1;
        clearDelegateSyncProbe();
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret);
        if (dot >= 0 && viewer != null)
            scheduleMemberAccessReload(viewer, dot);
        ContentAssistDebug.log("memberAccess transition dot=" + dot + " caret=" + caret); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void invalidateInterimListCache(ITextViewer viewer)
    {
        lastStableEmptyList = EMPTY;
        lastStableDelegateList = EMPTY;
        clearDelegateSyncProbe();
        if (viewer != null && !fullListComplete)
            scheduleEagerFullListLoad(viewer);
    }

    /** Полный список у якоря (без префикса) — для delegateOrderMap и штатного priority. */
    void scheduleEagerFullListLoad(ITextViewer viewer)
    {
        if (viewer == null || fullListComplete)
            return;
        if (!SmartAssistFilterState.isSmartFilterEnabled())
            return;
        org.eclipse.swt.widgets.Control widget = viewer.getTextWidget()
            instanceof org.eclipse.swt.widgets.Control
            ? (org.eclipse.swt.widgets.Control) viewer.getTextWidget()
            : null;
        if (widget == null || widget.isDisposed())
            return;
        org.eclipse.swt.widgets.Display display = widget.getDisplay();
        if (display == null || display.isDisposed())
            return;
        if (pendingEagerLoadTask != null)
            return;

        final int contextKey = fullListContextKey;
        pendingEagerLoadTask = () -> {
            pendingEagerLoadTask = null;
            if (widget.isDisposed() || fullListComplete || contextKey != fullListContextKey)
                return;
            loadFullList(viewer, false);
            if (fullListReady)
                ContentAssistSessionReloader.refreshPopupIfOpen();
        };
        display.asyncExec(pendingEagerLoadTask);
    }

    /** Отложенная загрузка полного списка на UI-потоке (не в hot path ввода). */
    public void scheduleLoadFullListCache(ITextViewer viewer)
    {
        if (fullListComplete && fullListCache.length > 0)
            return;
        int caret = resolveWidgetCaret(viewer);
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret);
        rescheduleIdleFullListLoad(viewer, false);
    }

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            // #region agent log
            ContentAssistDebug.agentLog("H3", "computeCompletionProposals", "comfortOff", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"offset\":" + offset + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return delegate.computeCompletionProposals(viewer, offset);
        }

        int literalCaret = resolveInvocationCaret(viewer, offset);
        boolean inLiteral = isStringLiteralAssistContext(viewer, literalCaret);
        String delegateName = delegate != null ? delegate.getClass().getSimpleName() : "null"; //$NON-NLS-1$
        // #region agent log
        ContentAssistDebug.debugModeLog("H61", "computeCompletionProposals", "enter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"offset\":" + offset + ",\"literalCaret\":" + literalCaret //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"inLiteral\":" + inLiteral //$NON-NLS-1$
                + ",\"delegate\":\"" + ContentAssistDebug.jsonEscapeForLog(delegateName) //$NON-NLS-1$
                + "\",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean repeatJFace = RepeatedInvocationDetect.isActive();
        ContentAssistDebug.debugModeLog("H62", "computeCompletionProposals", //$NON-NLS-1$ //$NON-NLS-2$
            repeatJFace ? "repeatProbe" : "repeatMiss", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"offset\":" + offset + ",\"inLiteral\":" + inLiteral //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"repeatJFace\":" + repeatJFace //$NON-NLS-1$
                + ",\"stackHint\":\"" + ContentAssistDebug.jsonEscapeForLog(repeatInvocationStackHint()) //$NON-NLS-1$
                + "\",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        ContentAssistSessionReloader reloader = viewer instanceof SourceViewer sv
            ? ContentAssistSessionReloader.forViewer(sv) : null;
        boolean manualDetect = ManualInvocationDetect.isActive();
        debugLiteralContext(viewer, literalCaret);
        // #region agent log
        ContentAssistDebug.debugModeLog("H1", "computeCompletionProposals", "gate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"offset\":" + offset + ",\"literalCaret\":" + literalCaret //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"inLiteral\":" + inLiteral + ",\"manualDetect\":" + manualDetect //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"reloaderNull\":" + (reloader == null) //$NON-NLS-1$
                + ",\"pending\":" + (reloader != null && reloader.isManualIrAssistPending()) //$NON-NLS-1$
                + ",\"irOnly\":" + irOnlyManualMode //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (manualDetect && reloader != null)
            reloader.tryBeginManualDualAssist(literalCaret);
        if (reloader != null && reloader.isManualIrAssistPending()
            && !isIrWordsResolvedForContext())
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H1", "computeCompletionProposals", "pendingEmpty", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + literalCaret + ",\"inLiteral\":" + inLiteral + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return EMPTY;
        }
        if (irOnlyManualMode)
            return computeIrOnlyProposals(viewer, literalCaret);
        if (ContentAssistSessionReloader.consumeLiteralRepeatFromCommand() && inLiteral)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H62", "computeCompletionProposals", "literalCommandBypass", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"offset\":" + offset + ",\"literalCaret\":" + literalCaret //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return computeLiteralRepeatWithoutDelegate(viewer, literalCaret);
        }
        // #region agent log
        ContentAssistDebug.debugModeLog("H9", "computeCompletionProposals", "enter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"offset\":" + offset + ",\"processor\":" + System.identityHashCode(this) //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"irOnly\":" + irOnlyManualMode + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        // #region agent log
        ContentAssistDebug.debugModeLog("H3", "computeCompletionProposals", "enter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"offset\":" + offset + ",\"inLiteral\":" + inLiteral //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"irResolved\":" + isIrWordsResolvedForContext() //$NON-NLS-1$
                + ",\"ctxKey\":" + fullListContextKey //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"irCtxKey\":" + irSnapshotContextKey + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (RepeatedInvocationDetect.isActive())
        {
            int widgetCaret = resolveWidgetCaret(viewer);
            int caretProbe = resolveInvocationCaret(viewer, offset);
            SourceViewer sourceViewer = viewer instanceof SourceViewer sv ? sv : null;
            BslXtextEditor repeatEditor = resolveEditorForLiteralAudit(viewer);
            boolean irConnected = sourceViewer != null
                && IrBslExpressionHtmlSupport.resolveIrSessionForAssist(repeatEditor, sourceViewer) != null;
            boolean repeatLiteral = isStringLiteralAssistContext(viewer, caretProbe) && irConnected;
            if (repeatLiteral)
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H56", "computeCompletionProposals", "repeatBranch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"branch\":\"literalRepeat\",\"offset\":" + offset //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"literalCaret\":" + caretProbe //$NON-NLS-1$
                        + ",\"widgetCaret\":" + widgetCaret //$NON-NLS-1$
                        + ",\"smartBefore\":" + SmartAssistFilterState.isSmartFilterEnabled() //$NON-NLS-1$
                        + ",\"irConnected\":" + irConnected //$NON-NLS-1$
                        + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
                if (assistant != null)
                    ContentAssistPopupSync.captureSelectionBeforeFilterToggle(assistant);
                SmartAssistFilterState.toggle();
                ContentAssistSessionReloader.scheduleFilterToggleUiSync();
                int caret = widgetCaret >= 0 ? widgetCaret : caretProbe;
                return computeLiteralPopupRefresh(viewer, offset, caret);
            }
            if (!isStringLiteralAssistContext(viewer, caretProbe))
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H56", "computeCompletionProposals", "repeatBranch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"branch\":\"memberRepeat\",\"offset\":" + offset //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"literalCaret\":" + caretProbe //$NON-NLS-1$
                        + ",\"widgetCaret\":" + widgetCaret //$NON-NLS-1$
                        + ",\"smartBefore\":" + SmartAssistFilterState.isSmartFilterEnabled() //$NON-NLS-1$
                        + ",\"hasIr\":" + hasIrProposalsForCurrentContext() //$NON-NLS-1$
                        + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
                if (assistant != null)
                    ContentAssistPopupSync.captureSelectionBeforeFilterToggle(assistant);
                final boolean wasSmart = SmartAssistFilterState.isSmartFilterEnabled();
                SmartAssistFilterState.toggle();
                ContentAssistSessionReloader.scheduleFilterToggleUiSync();
                int caret = caretProbe;
                primeAssistContext(viewer, caret);
                IDocument doc = viewer == null ? null : viewer.getDocument();
                String filter = SmartFilterTracker.getCurrentFilter();
                ensureFullListForContext(viewer, doc, caret);
                if (wasSmart)
                    applyUnfilteredReloadIfNeeded(viewer);
                int probeOffset = resolveDelegateProbeOffset(viewer, offset, caret);
                boolean syncSmart = wasSmart || SmartAssistFilterState.isSmartFilterEnabled();
                ICompletionProposal[] kept = resolveProposalList(viewer, probeOffset, caret, filter,
                    syncSmart);
                return kept;
            }
        }

        if (inLiteral)
        {
            ICompletionProposal[] passthrough = computeLiteralPassthrough(viewer, offset, literalCaret);
            // #region agent log
            ContentAssistDebug.agentLog("H2", "computeCompletionProposals", "literalBypass", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"runId\":\"post-fix\",\"offset\":" + offset + ",\"literalCaret\":" + literalCaret //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"n\":" + (passthrough != null ? passthrough.length : -1) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return passthrough;
        }

        return computeProposalsLight(viewer, offset);
    }

    /**
     * Явное обновление popup (toggle, Ctrl+Space, догрузка кэша): может вернуть
     * уже отфильтрованный список (toggle, debounced ввод, догрузка кэша).
     */
    public ICompletionProposal[] computeForPopupRefresh(ITextViewer viewer, int offset)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return delegate.computeCompletionProposals(viewer, offset);

        int caret = offset >= 0 ? clampCaret(viewer != null ? viewer.getDocument() : null, offset)
            : resolveWidgetCaret(viewer);
        if (isStringLiteralAssistContext(viewer, caret))
        {
            if (irOnlyManualMode)
                return computeIrOnlyProposals(viewer, caret);
            return computeLiteralPopupRefresh(viewer, offset, caret);
        }
        primeFilterTrackerOnly(viewer, caret);
        IDocument doc = viewer == null ? null : viewer.getDocument();
        String filter = SmartFilterTracker.getCurrentFilter();

        ensureFullListForContext(viewer, doc, caret, false);

        applyUnfilteredReloadIfNeeded(viewer);

        int probeOffset = resolveDelegateProbeOffset(viewer, offset, caret);
        ICompletionProposal[] result = resolveProposalList(viewer, probeOffset, caret, filter,
            SmartAssistFilterState.isSmartFilterEnabled());
        return result;
    }

    /** Popup/toggle в литерале+ИР: smart ON — по префиксу, OFF — полный merged. */
    private ICompletionProposal[] computeLiteralPopupRefresh(ITextViewer viewer, int offset, int caret)
    {
        return computeLiteralPassthrough(viewer, offset, caret);
    }

    /** Repeat Ctrl+Space в literal: список из кэша, без delegate (EDT ModeAware.nextMode). */
    private ICompletionProposal[] computeLiteralRepeatWithoutDelegate(ITextViewer viewer, int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret, false);
        String filter = SmartFilterTracker.getCurrentFilter();
        boolean stockOnly = !hasIrProposalsForCurrentContext() && !isIrWordsResolvedForContext();
        // #region agent log
        ContentAssistDebug.debugModeLog("H70", "computeLiteralRepeatWithoutDelegate", "enter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"stockOnly\":" + stockOnly //$NON-NLS-1$
                + ",\"stableN\":" + lastStableDelegateList.length //$NON-NLS-1$
                + ",\"delegateCacheN\":" + delegateListCache.length //$NON-NLS-1$
                + ",\"smart\":" + SmartAssistFilterState.isSmartFilterEnabled() //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (stockOnly)
        {
            ICompletionProposal[] stable = unwrapStableDelegateBase();
            if (stable.length > 0)
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H62", "computeLiteralRepeatWithoutDelegate", "stockRepeat", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"stableN\":" + stable.length //$NON-NLS-1$
                        + ",\"smart\":" + SmartAssistFilterState.isSmartFilterEnabled() //$NON-NLS-1$
                        + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return applyLiteralSmartFilter(stable, filter);
            }
            if (delegateListCache.length > 0 && isDelegateSyncProbedForContext())
            {
                return applyLiteralSmartFilter(
                    stripIrProposals(unwrapProposals(delegateListCache)), filter);
            }
            // #region agent log
            ContentAssistDebug.debugModeLog("H62", "computeLiteralRepeatWithoutDelegate", "returnEmpty", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"stockOnly\":true,\"stableN\":0" //$NON-NLS-1$
                    + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return EMPTY;
        }
        if (SmartAssistFilterState.isSmartFilterEnabled())
        {
            ICompletionProposal[] filtered = filterCachedProposalsForPopup(viewer, caret, filter);
            if (filtered != null)
                return filtered;
        }
        if (isCacheValidForCaret(doc, caret) && fullListCache.length > 0)
            return finalizeListForIrAssistDisplay(fullListCache);
        ICompletionProposal[] interim = interimSourceForCaret(doc, caret);
        if (interim != null)
            return finalizeListForIrAssistDisplay(mergeIrForDisplay(unwrapProposals(interim)));
        ICompletionProposal[] fallback = unwrapStableDelegateBase();
        if (fallback.length > 0)
            return applyLiteralSmartFilter(fallback, filter);
        // #region agent log
        ContentAssistDebug.debugModeLog("H62", "computeLiteralRepeatWithoutDelegate", "returnEmpty", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"stockOnly\":false,\"stableN\":0" //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        return EMPTY;
    }

    private ICompletionProposal[] applyLiteralSmartFilter(ICompletionProposal[] merged, String filter)
    {
        if (!SmartAssistFilterState.isSmartFilterEnabled() || filter == null || filter.isEmpty())
            return finalizeListForIrAssistDisplay(merged);
        return filterAndSort(merged, filter);
    }

    /** EDT delegate в литерале пуст на всех probe-offset'ах. */
    boolean probeLiteralDelegateEmpty(ITextViewer viewer, int caret)
    {
        return probeLiteralDelegateBest(viewer, caret, caret).length == 0;
    }

    /** В литерале — delegate + merge ИР; EDT пуст + ИР in-flight → EMPTY без beep. */
    private ICompletionProposal[] computeLiteralPassthrough(ITextViewer viewer, int offset, int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret, false);
        String filter = SmartFilterTracker.getCurrentFilter();
        ICompletionProposal[] best = probeLiteralDelegateBest(viewer, offset, caret);
        ICompletionProposal[] edtClean = stripIrProposals(unwrapProposals(best));
        int edtN = edtClean.length;
        int irN = isIrWordsResolvedForContext() ? irProposals.length : 0;
        if (isIrWordsResolvedForContext() && irProposals.length > 0)
        {
            String cacheSource = "probe"; //$NON-NLS-1$
            ICompletionProposal[] merged;
            if (edtN == 0 && fullListReady && fullListCache.length > 0
                && irSnapshotContextKey == fullListContextKey)
            {
                merged = fullListCache;
                cacheSource = "fullListCache"; //$NON-NLS-1$
                edtN = -1;
            }
            else if (edtN == 0 && delegateListCache.length > 0
                && irSnapshotContextKey == fullListContextKey)
            {
                merged = mergeIrForDisplay(delegateListCache);
                cacheSource = "delegateCache"; //$NON-NLS-1$
                edtN = delegateListCache.length;
            }
            else
                merged = mergeIrForDisplay(edtClean);
            ICompletionProposal[] result = applyLiteralSmartFilter(merged, filter);
            // #region agent log
            int edtLeak = countEdtConfigurableProposals(result);
            if (edtLeak > 0)
            {
                ContentAssistDebug.debugModeLog("H64", "computeLiteralPassthrough", "edtLeak", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"edtConfigurableN\":" + edtLeak //$NON-NLS-1$
                        + ",\"mergedN\":" + result.length //$NON-NLS-1$
                        + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            ContentAssistDebug.debugModeLog("H2", "computeLiteralPassthrough", "merged", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"edtN\":" + edtN + ",\"irN\":" + irN //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"mergedN\":" + result.length //$NON-NLS-1$
                    + ",\"cache\":\"" + cacheSource + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            auditLiteralComputeReturn(viewer, resolveEditorForLiteralAudit(viewer),
                result.length, "computePassthrough", null); //$NON-NLS-1$
            return result;
        }
        ContentAssistSessionReloader reloader = viewer instanceof SourceViewer sv
            ? ContentAssistSessionReloader.forViewer(sv) : null;
        if (reloader != null && reloader.isManualIrAssistPending() && edtN == 0
            && !isIrWordsResolvedForContext())
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H2", "computeLiteralPassthrough", "pendingEmpty", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"edtN\":" + edtN + ",\"irN\":" + irN + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return EMPTY;
        }
        // #region agent log
        ContentAssistDebug.debugModeLog("H2", "computeLiteralPassthrough", "edtOnly", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"edtN\":" + edtN + ",\"irN\":" + irN + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        ICompletionProposal[] result = edtN > 0 ? edtClean : best;
        if (result != null && result.length > 0)
            rememberInterimDelegateList(result);
        // #region agent log
        ContentAssistDebug.debugModeLog("H70", "computeLiteralPassthrough", "edtOnlyStable", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"edtN\":" + edtN //$NON-NLS-1$
                + ",\"lastStableN\":" + lastStableDelegateList.length //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (isIrWordsResolvedForContext() && irN > 0)
        {
            auditLiteralComputeReturn(viewer, resolveEditorForLiteralAudit(viewer),
                result != null ? result.length : 0, "computePassthrough", "edtOnlyDespiteIr"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    private ICompletionProposal[] probeLiteralDelegateBest(ITextViewer viewer, int offset, int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        addProbeOffset(set, offset);
        addProbeOffset(set, caret);
        if (assistant != null)
        {
            addProbeOffset(set, ContentAssistPopupSync.getInvocationOffset(assistant));
            addProbeOffset(set, ContentAssistPopupSync.getFilterOffset(assistant));
        }
        int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
        if (dot >= 0)
            addProbeOffset(set, dot + 1);
        ICompletionProposal[] best = EMPTY;
        for (Integer off : set)
        {
            if (off == null || off < 0)
                continue;
            ICompletionProposal[] raw = delegate.computeCompletionProposals(viewer, off);
            int count = raw != null ? raw.length : 0;
            if (count > best.length)
                best = raw != null ? raw : EMPTY;
        }
        return best;
    }

    /**
     * После снятия smart-фильтра: сброс interim-кэша и синхронная догрузка полного списка.
     * {@link #rescheduleIdleFullListLoad} при непустом префиксе не вызывается — только прямой {@link #loadFullList}.
     */
    private void applyUnfilteredReloadIfNeeded(ITextViewer viewer)
    {
        if (!SmartAssistFilterState.isUnfilteredReloadPending())
            return;
        lastStableEmptyList = EMPTY;
        lastStableDelegateList = EMPTY;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int caret = resolveWidgetCaret(viewer);
        int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
        boolean reload = shouldReloadDelegateOnUnfiltered();
        if (reload)
        {
            fullListReady = false;
            fullListComplete = false;
            assignFullListCache(EMPTY);
            memberAccessReloadScheduledSeq = -1;
            clearDelegateSyncProbe();
            if (dot < 0)
                loadFullList(viewer, true);
            // member-access: полный список — в async fetchStockDelegateList (без sync probe на Ctrl+Space)
        }
        SmartAssistFilterState.clearUnfilteredReloadPending();
    }

    /**
     * Кэш готов — smart-фильтр по полному списку.
     * Кэш не готов — штатный список delegate (popup не пустой), догрузка кэша отложена.
     */
    private ICompletionProposal[] resolveProposalList(ITextViewer viewer, int offset, int caret,
                                                      String filter, boolean smartEnabled)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (doc != null && caret >= 0)
            filter = computeIdentifierFilter(doc, caret);

        if (terminalEmptyMemberAccess && doc != null && caret >= 0)
        {
            int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
            if (dot >= 0 && cachedNonObjectDot == dot)
            {
                debugResolveExit(doc, caret, filter, 0, false, "terminalEmpty", EMPTY); //$NON-NLS-1$
                return EMPTY;
            }
        }

        if (!smartEnabled)
            return resolveAlphabeticalList(viewer, offset, caret);
        if (filter.isEmpty())
            return resolveDelegateOrderedList(viewer, offset, caret);

        scheduleEagerFullListLoad(viewer);
        if (isCacheValidForCaret(doc, caret))
        {
            ICompletionProposal[] result = filterAndSort(fullListCache, filter);
            debugResolveExit(doc, caret, filter, fullListCache.length, true, "cacheValid", result); //$NON-NLS-1$
            return result;
        }

        ICompletionProposal[] interim = interimSourceForCaret(doc, caret);
        if (interim != null)
        {
            ICompletionProposal[] result = filterAndSort(
                mergeIrForDisplay(unwrapProposals(interim)), filter);
            debugResolveExit(doc, caret, filter, interim.length, false, "interim", result); //$NON-NLS-1$
            return result;
        }
        if (isDelegateSyncProbedForContext())
        {
            if (hasIrProposalsForCurrentContext())
            {
                ICompletionProposal[] result = filterAndSort(mergeIrForDisplay(EMPTY), filter);
                debugResolveExit(doc, caret, filter, 0, false, "delegateProbedIr", result); //$NON-NLS-1$
                return result;
            }
            debugResolveExit(doc, caret, filter, 0, false, "delegateProbed", EMPTY); //$NON-NLS-1$
            return EMPTY;
        }
        ICompletionProposal[] raw = unwrapProposals(fetchDelegateList(viewer, offset, caret));
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null || !reloader.isWordsTableFetchInFlightForContext(caret))
            markDelegateSyncProbed();
        rememberInterimDelegateList(raw);
        absorbInterimIntoCache(viewer, caret, raw);
        ICompletionProposal[] filtered = filterAndSort(mergeIrForDisplay(raw), filter);
        if (filtered.length > 0)
        {
            debugResolveExit(doc, caret, filter, raw.length, false, "fetchFiltered", filtered); //$NON-NLS-1$
            return filtered;
        }
        if (fullListCache.length > 0)
        {
            ICompletionProposal[] result = filterAndSort(fullListCache, filter);
            debugResolveExit(doc, caret, filter, fullListCache.length, true, "fullListFallback", result); //$NON-NLS-1$
            return result;
        }
        debugResolveExit(doc, caret, filter, raw.length, false, "fetchEmpty", EMPTY); //$NON-NLS-1$
        return EMPTY;
    }

    private ICompletionProposal[] interimSourceForCaret(IDocument doc, int caret)
    {
        if (doc == null || caret < 0)
            return null;
        if (computeFullListContextKey(doc, caret) != fullListContextKey)
            return null;
        if (fullListCache.length > 0)
            return fullListCache;
        if (lastStableDelegateList.length > 0)
            return lastStableDelegateList;
        return null;
    }

    private void rememberInterimDelegateList(ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return;
        ICompletionProposal[] built = buildDelegateOrderedList(unwrapProposals(raw));
        if (built.length > lastStableDelegateList.length)
            lastStableDelegateList = built;
        // #region agent log
        ContentAssistDebug.debugModeLog("H70", "rememberInterimDelegateList", "remember", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"rawN\":" + raw.length //$NON-NLS-1$
                + ",\"stableNAfter\":" + lastStableDelegateList.length //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private boolean isCacheValidForCaret(IDocument doc, int caret)
    {
        return fullListReady && fullListCache.length > 0
            && doc != null && caret >= 0
            && computeFullListContextKey(doc, caret) == fullListContextKey;
    }

    private void absorbInterimIntoCache(ITextViewer viewer, int caret, ICompletionProposal[] raw)
    {
        if (fullListComplete || raw == null || raw.length == 0)
            return;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (doc == null || caret < 0)
            return;
        if (computeFullListContextKey(doc, caret) != fullListContextKey)
            return;
        ICompletionProposal[] unwrapped = unwrapProposals(raw);
        if (irProposals.length > 0 && irSnapshotContextKey == fullListContextKey)
            unwrapped = stripEmptyPlaceholderProposals(unwrapped);
        if (unwrapped.length > fullListCache.length)
        {
            assignFullListCache(unwrapped);
            fullListReady = true;
        }
    }

    private void assignFullListCache(ICompletionProposal[] cache)
    {
        ICompletionProposal[] stock = stripIrProposals(cache != null ? cache : EMPTY);
        if (irProposals.length > 0 && irSnapshotContextKey == fullListContextKey)
            stock = stripEmptyPlaceholderProposals(stock);
        delegateListCache = stock;
        rebuildMergedFullListCache();
    }

    private void rebuildMergedFullListCache()
    {
        fullListCache = mergeIrProposals(delegateListCache);
        rebuildDelegateOrderMap();
    }

    private static ICompletionProposal[] stripIrProposals(ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;
        List<ICompletionProposal> kept = new ArrayList<>(raw.length);
        for (ICompletionProposal p : raw)
        {
            if (!(unwrapProposal(p) instanceof IrCompletionProposal))
                kept.add(p);
        }
        return kept.isEmpty() ? EMPTY : kept.toArray(new ICompletionProposal[kept.size()]);
    }

    /** JFace {@code CompletionProposalPopup$EmptyProposal} («нет вариантов»). */
    static boolean isEmptyPlaceholderProposal(ICompletionProposal proposal)
    {
        ICompletionProposal raw = unwrapProposal(proposal);
        if (raw == null || raw instanceof IrCompletionProposal)
            return false;
        String cn = raw.getClass().getName();
        if (cn.contains("CompletionProposalPopup$EmptyProposal")) //$NON-NLS-1$
            return true;
        String display = raw.getDisplayString();
        if (display == null || display.isEmpty())
            return false;
        String name = BslCompletionSideHintResolver.extractProposalName(display);
        if (name == null)
            name = display.trim();
        return isEmptyPlaceholderDisplayName(name);
    }

    private static boolean isEmptyPlaceholderDisplayName(String name)
    {
        if (name == null || name.isEmpty())
            return false;
        if ("no proposals".equalsIgnoreCase(name)) //$NON-NLS-1$
            return true;
        if ("нет вариантов".equalsIgnoreCase(name)) //$NON-NLS-1$
            return true;
        if ("без вариантов".equalsIgnoreCase(name)) //$NON-NLS-1$
            return true;
        return false;
    }

    static ICompletionProposal[] stripEmptyPlaceholderProposals(ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;
        List<ICompletionProposal> kept = new ArrayList<>(raw.length);
        ICompletionProposal lastStripped = null;
        for (ICompletionProposal p : raw)
        {
            if (isEmptyPlaceholderProposal(p))
                lastStripped = p;
            else
                kept.add(p);
        }
        if (lastStripped == null)
            return raw;
        logEmptyPlaceholderStrip(raw.length, kept.size(), lastStripped);
        return kept.isEmpty() ? EMPTY : kept.toArray(new ICompletionProposal[kept.size()]);
    }

    static List<ICompletionProposal> stripEmptyPlaceholderList(List<ICompletionProposal> raw)
    {
        if (raw == null || raw.isEmpty())
            return raw;
        ICompletionProposal[] stripped = stripEmptyPlaceholderProposals(
            raw.toArray(new ICompletionProposal[raw.size()]));
        if (stripped.length == raw.size())
            return raw;
        return new ArrayList<>(java.util.Arrays.asList(stripped));
    }

    private static final java.util.concurrent.atomic.AtomicInteger emptyPlaceholderStripLogs =
        new java.util.concurrent.atomic.AtomicInteger();

    private static void logEmptyPlaceholderStrip(int beforeN, int afterN,
                                                   ICompletionProposal lastStripped)
    {
        if (emptyPlaceholderStripLogs.incrementAndGet() > 10)
            return;
        ICompletionProposal raw = unwrapProposal(lastStripped);
        String display = raw != null ? raw.getDisplayString() : null;
        String cn = raw != null ? raw.getClass().getName() : ""; //$NON-NLS-1$
        // #region agent log
        ContentAssistDebug.debugModeLog("H77", "stripEmptyPlaceholder", "stripped", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"beforeN\":" + beforeN //$NON-NLS-1$
                + ",\"afterN\":" + afterN //$NON-NLS-1$
                + ",\"strippedN\":" + (beforeN - afterN) //$NON-NLS-1$
                + ",\"lastDisplay\":\"" + ContentAssistDebug.jsonEscapeForLog( //$NON-NLS-1$
                    display != null ? display : "") //$NON-NLS-1$
                + "\",\"lastClass\":\"" + ContentAssistDebug.jsonEscapeForLog(cn) //$NON-NLS-1$
                + "\",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private ICompletionProposal[] mergeIrProposals(ICompletionProposal[] delegateList)
    {
        // #region agent log
        ContentAssistDebug.sessionLog("H1", "mergeIrProposals", "enter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"irCount\":" + irProposals.length //$NON-NLS-1$
                + ",\"ctxMatch\":" + (irSnapshotContextKey == fullListContextKey) //$NON-NLS-1$
                + ",\"ctxKey\":" + fullListContextKey //$NON-NLS-1$
                + ",\"irCtxKey\":" + irSnapshotContextKey //$NON-NLS-1$
                + ",\"delegateCount\":" + (delegateList != null ? delegateList.length : 0) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (irProposals.length == 0 || irSnapshotContextKey != fullListContextKey)
            return delegateList != null ? delegateList : EMPTY;
        if (delegateList != null && delegateList.length > 0)
            delegateList = stripEmptyPlaceholderProposals(delegateList);
        applyStockAssistImagesFromDelegate(irProposals, delegateList);
        MemberEdtPriorityProfile delegateProfile = isIrAssistOrderingEnabled()
            ? MemberEdtPriorityProfile.analyze(delegateList)
            : MemberEdtPriorityProfile.NONE;
        if (delegateList == null || delegateList.length == 0)
        {
            Map<String, Integer> irPriorityByKey = buildIrPriorityByKey(irProposals);
            return stripEmptyPlaceholderProposals(
                sortMergedList(irProposals, irPriorityByKey, delegateProfile));
        }

        Map<String, Integer> irPriorityByKey = buildIrPriorityByKey(irProposals);
        Map<String, Deque<ICompletionProposal>> delegateByKey = indexDelegateByKey(delegateList);
        Set<ICompletionProposal> placedDelegates =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<ICompletionProposal> merged = new ArrayList<>(delegateList.length + irProposals.length);

        for (int i = 0; i < irProposals.length; i++)
        {
            ICompletionProposal irP = irProposals[i];
            String key = dedupKey(irP);
            Deque<ICompletionProposal> queue = key.isEmpty() ? null : delegateByKey.get(key);
            if (queue != null && !queue.isEmpty())
            {
                while (!queue.isEmpty())
                {
                    ICompletionProposal d = queue.removeFirst();
                    merged.add(d);
                    placedDelegates.add(unwrapProposal(d));
                }
            }
            else if (!key.isEmpty())
                merged.add(irP);
        }
        for (ICompletionProposal p : delegateList)
        {
            if (!placedDelegates.contains(unwrapProposal(p)))
                merged.add(p);
        }

        ICompletionProposal[] result = sortMergedList(
            merged.toArray(new ICompletionProposal[merged.size()]), irPriorityByKey,
            delegateProfile);
        // #region agent log
        debugSessionOrderSample("H2", "mergeIrProposals", "irOrderMerge", result, 12); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        debugIrMergeOrderSample("H3", "mergeIrProposals", "afterSort", result, irPriorityByKey, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            delegateProfile);
        // #endregion
        return stripEmptyPlaceholderProposals(result);
    }

    private static Map<String, Deque<ICompletionProposal>> indexDelegateByKey(
        ICompletionProposal[] delegateList)
    {
        Map<String, Deque<ICompletionProposal>> map = new HashMap<>();
        for (ICompletionProposal p : delegateList)
        {
            String key = dedupKey(p);
            if (key.isEmpty())
                continue;
            map.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(p);
        }
        return map;
    }

    private static Map<String, Integer> buildIrPriorityByKey(ICompletionProposal[] proposals)
    {
        Map<String, Integer> map = new HashMap<>();
        if (proposals == null)
            return map;
        for (ICompletionProposal p : proposals)
        {
            ICompletionProposal raw = unwrapProposal(p);
            if (!(raw instanceof IrCompletionProposal ir))
                continue;
            String key = dedupKey(p);
            if (key.isEmpty())
                continue;
            map.merge(key, ir.getIrPriority(), Math::max);
        }
        return map;
    }

    private ICompletionProposal[] sortMergedList(ICompletionProposal[] merged,
                                                 Map<String, Integer> irPriorityByKey,
                                                 MemberEdtPriorityProfile profile)
    {
        return sortListForIrAssist(merged, irPriorityByKey, profile);
    }

    /**
     * Временно: без второго ключа compare, когда в merge участвуют слова ИР
     * ({@code irPriorityByKey} непустой).
     */
    private static final boolean IR_ASSIST_SKIP_EDT_SORT_WITH_IR_WORDS = true;

    private static boolean useEdtSortKey(Map<String, Integer> irPriorityByKey)
    {
        if (!IR_ASSIST_SKIP_EDT_SORT_WITH_IR_WORDS)
            return true;
        return irPriorityByKey == null || irPriorityByKey.isEmpty();
    }

    /** ИР.Приоритет ↓, [EDT.Приоритет ↓], алфавит ↑. */
    private ICompletionProposal[] sortListForIrAssist(ICompletionProposal[] raw,
                                                      Map<String, Integer> irPriorityByKey)
    {
        MemberEdtPriorityProfile profile = isIrAssistOrderingEnabled()
            ? MemberEdtPriorityProfile.analyze(raw)
            : MemberEdtPriorityProfile.NONE;
        return sortListForIrAssist(raw, irPriorityByKey, profile);
    }

    private ICompletionProposal[] sortListForIrAssist(ICompletionProposal[] raw,
                                                      Map<String, Integer> irPriorityByKey,
                                                      MemberEdtPriorityProfile profile)
    {
        if (raw == null || raw.length <= 1)
            return raw != null ? raw : EMPTY;
        final MemberEdtPriorityProfile sortProfile = profile != null
            ? profile
            : MemberEdtPriorityProfile.NONE;
        Map<String, Integer> irMap = irPriorityByKey != null
            ? irPriorityByKey
            : java.util.Collections.emptyMap();
        ICompletionProposal[] copy = raw.clone();
        Arrays.sort(copy, (a, b) -> compareForIrAssistList(a, b, irMap, sortProfile));
        return copy;
    }

    private static int compareForIrAssistList(ICompletionProposal p1, ICompletionProposal p2,
                                              Map<String, Integer> irPriorityByKey,
                                              MemberEdtPriorityProfile profile)
    {
        Map<String, Integer> irMap = irPriorityByKey != null
            ? irPriorityByKey
            : java.util.Collections.emptyMap();
        int ir1 = resolveIrPriorityForMerge(p1, irMap);
        int ir2 = resolveIrPriorityForMerge(p2, irMap);
        if (ir1 != ir2)
            return Integer.compare(ir2, ir1);
        if (useEdtSortKey(irMap))
        {
            int edt = compareEdtPriorityForIrAssist(p1, p2, profile);
            if (edt != 0)
                return edt;
        }
        return dedupKey(p1).compareToIgnoreCase(dedupKey(p2));
    }

    private static int compareEdtPriorityForIrAssist(ICompletionProposal p1,
                                                     ICompletionProposal p2,
                                                     MemberEdtPriorityProfile profile)
    {
        int pr1 = profile.effectiveEdtForIrSort(p1);
        int pr2 = profile.effectiveEdtForIrSort(p2);
        if (pr1 != pr2)
            return Integer.compare(pr2, pr1);
        return 0;
    }

    private static boolean isIrAssistOrderingEnabled()
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return false;
        BslXtextEditor editor = reloader.getBslEditor();
        return IrBslExpressionHtmlSupport.resolveConnectedSession(editor) != null;
    }

    /**
     * Доминирующий EDT-priority в выборке (первые {@link #DOMINANT_SAMPLE_LIMIT} свойств/методов);
     * подмена propertyDominant → methodDominant только для сортировки при ИР.
     */
    private static final class MemberEdtPriorityProfile
    {
        private static final double DOMINANT_SHARE = 0.8;
        private static final int DOMINANT_SAMPLE_LIMIT = 50;

        static final MemberEdtPriorityProfile NONE = new MemberEdtPriorityProfile(false, 0, 0);

        private final boolean remapActive;
        private final int propertyDominant;
        private final int methodDominant;

        private MemberEdtPriorityProfile(boolean remapActive, int propertyDominant,
                                        int methodDominant)
        {
            this.remapActive = remapActive;
            this.propertyDominant = propertyDominant;
            this.methodDominant = methodDominant;
        }

        boolean isRemapActive()
        {
            return remapActive;
        }

        int methodDominantValue()
        {
            return methodDominant;
        }

        int propertyDominantValue()
        {
            return propertyDominant;
        }

        static MemberEdtPriorityProfile analyze(ICompletionProposal[] batch)
        {
            if (batch == null || batch.length == 0)
                return NONE;
            int[] propertyPriorities = new int[DOMINANT_SAMPLE_LIMIT];
            int propertyCount = 0;
            int[] methodPriorities = new int[DOMINANT_SAMPLE_LIMIT];
            int methodCount = 0;
            for (ICompletionProposal p : batch)
            {
                ICompletionProposal raw = unwrapProposal(p);
                if (!(raw instanceof ConfigurableCompletionProposal configurable))
                    continue;
                String kind = BslCompletionSideHintResolver.resolveElementKind(p);
                int pri = configurable.getPriority();
                if (IrBslExpressionHtmlSupport.KIND_PROPERTY.equals(kind))
                {
                    if (propertyCount < DOMINANT_SAMPLE_LIMIT)
                        propertyPriorities[propertyCount++] = pri;
                }
                else if (IrBslExpressionHtmlSupport.KIND_METHOD.equals(kind))
                {
                    if (methodCount < DOMINANT_SAMPLE_LIMIT)
                        methodPriorities[methodCount++] = pri;
                }
            }
            Integer propDom = dominantPriority(propertyPriorities, propertyCount);
            Integer methDom = dominantPriority(methodPriorities, methodCount);
            if (propDom != null && methDom != null && propDom.intValue() != methDom.intValue())
                return new MemberEdtPriorityProfile(true, propDom.intValue(), methDom.intValue());
            if (methDom != null)
                return new MemberEdtPriorityProfile(false, 0, methDom.intValue());
            if (propDom != null)
                return new MemberEdtPriorityProfile(false, propDom.intValue(), 0);
            return NONE;
        }

        private static Integer dominantPriority(int[] priorities, int count)
        {
            if (count < 2)
                return null;
            Map<Integer, Integer> freq = new HashMap<>();
            for (int i = 0; i < count; i++)
                freq.merge(priorities[i], 1, Integer::sum);
            for (Map.Entry<Integer, Integer> e : freq.entrySet())
            {
                if (e.getValue().intValue() > count * DOMINANT_SHARE)
                    return e.getKey();
            }
            return null;
        }

        int effectiveEdtPriority(ICompletionProposal proposal)
        {
            ICompletionProposal raw = unwrapProposal(proposal);
            if (!(raw instanceof ConfigurableCompletionProposal configurable))
                return Integer.MIN_VALUE;
            int pri = configurable.getPriority();
            if (!remapActive)
                return pri;
            String kind = BslCompletionSideHintResolver.resolveElementKind(proposal);
            if (IrBslExpressionHtmlSupport.KIND_PROPERTY.equals(kind)
                && pri == propertyDominant)
                return methodDominant;
            return pri;
        }

        /** EDT-полоса для сортировки merge: Configurable + IrCompletionProposal. */
        int effectiveEdtForIrSort(ICompletionProposal proposal)
        {
            ICompletionProposal raw = unwrapProposal(proposal);
            if (raw instanceof ConfigurableCompletionProposal)
                return effectiveEdtPriority(proposal);
            if (raw instanceof IrCompletionProposal ir)
                return ir.isMethod() ? methodBandForIrSort() : propertyBandForIrSort();
            return Integer.MIN_VALUE;
        }

        private int methodBandForIrSort()
        {
            return methodDominant != 0 ? methodDominant : propertyDominant;
        }

        private int propertyBandForIrSort()
        {
            if (remapActive)
                return methodDominant;
            return propertyDominant != 0 ? propertyDominant : methodDominant;
        }
    }

    private static int resolveIrPriorityForMerge(ICompletionProposal proposal,
                                                 Map<String, Integer> irPriorityByKey)
    {
        ICompletionProposal raw = unwrapProposal(proposal);
        if (raw instanceof IrCompletionProposal ir)
            return ir.getIrPriority();
        String key = dedupKey(proposal);
        if (!key.isEmpty() && irPriorityByKey != null)
        {
            Integer inherited = irPriorityByKey.get(key);
            if (inherited != null)
                return inherited.intValue();
        }
        return Integer.MIN_VALUE;
    }

    private static String dedupKey(ICompletionProposal proposal)
    {
        ICompletionProposal raw = unwrapProposal(proposal);
        if (raw instanceof IrCompletionProposal ir)
            return IrBslCompletionSupport.normalizeFilterKey(ir.getFilterName());
        return IrBslCompletionSupport.normalizeFilterKey(
            BslCompletionSideHintResolver.extractProposalName(raw.getDisplayString()));
    }

    static String dedupKeyForMerge(ICompletionProposal proposal)
    {
        return dedupKey(proposal);
    }

    static String firstProposalDedupKey(ICompletionProposal[] proposals)
    {
        if (proposals == null || proposals.length == 0)
            return ""; //$NON-NLS-1$
        return dedupKey(proposals[0]);
    }

    private void rebuildDelegateOrderMap()
    {
        if (fullListCache.length == 0)
        {
            delegateOrderMap = null;
            return;
        }
        IdentityHashMap<ICompletionProposal, Integer> map =
            new IdentityHashMap<>(fullListCache.length * 2);
        for (int i = 0; i < fullListCache.length; i++)
            map.put(fullListCache[i], i);
        delegateOrderMap = map;
    }

    int lookupDelegateOrder(ICompletionProposal proposal)
    {
        if (delegateOrderMap == null || proposal == null)
            return -1;
        Integer idx = delegateOrderMap.get(unwrapProposal(proposal));
        return idx != null ? idx.intValue() : -1;
    }

    /** Штатный список delegate при выключенном smart-фильтре (порядок EDT, не smart-сортировка). */
    private ICompletionProposal[] resolveAlphabeticalList(ITextViewer viewer, int offset, int caret)
    {
        return fetchStockDelegateList(viewer, offset, caret);
    }

    private ICompletionProposal[] fetchStockDelegateList(ITextViewer viewer, int offset, int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        int[] offsets = stockProbeOffsets(assistant, caret, dot, doc);
        ICompletionProposal[] raw = probeDelegateAtOffsets(viewer, offsets, dot);
        int probe = -1;
        if (raw.length == 0)
        {
            probe = resolveDelegateProbeOffset(viewer, offset, caret);
            ICompletionProposal[] fallback = delegate.computeCompletionProposals(viewer, probe);
            raw = unwrapProposals(fallback != null ? fallback : EMPTY);
        }
        if (dot >= 0)
        {
            if (memberStockFullListDot == dot && memberStockFullList.length > raw.length)
                raw = memberStockFullList;
            else if (raw.length < MIN_STABLE_MEMBER_CACHE)
            {
                boolean allowShift = canShiftCaretForMemberCapture(viewer);
                captureMemberStockAtDot(viewer, dot, allowShift);
                if (!allowShift)
                    scheduleMemberStockCapture(viewer, dot);
                if (memberStockFullListDot == dot && memberStockFullList.length > raw.length)
                    raw = memberStockFullList;
            }
        }
        else if (isCacheValidForCaret(doc, caret) && fullListCache.length > raw.length)
            raw = fullListCache;
        return finalizeListForIrAssistDisplay(raw);
    }

    /** Probe для штатного списка: invocation / filter offset / начало слова / каретка. */
    private static int[] stockProbeOffsets(ContentAssistant assistant, int caret, int dot,
                                           IDocument doc)
    {
        if (dot >= 0)
            return memberAccessProbeOffsets(caret, dot, assistant, doc);
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        addProbeOffset(set, caret);
        if (assistant != null)
        {
            addProbeOffset(set, ContentAssistPopupSync.getInvocationOffset(assistant));
            addProbeOffset(set, ContentAssistPopupSync.getFilterOffset(assistant));
        }
        if (doc != null)
        {
            String prefix = computeIdentifierFilter(doc, caret);
            if (!prefix.isEmpty())
                addProbeOffset(set, Math.max(0, caret - prefix.length()));
        }
        int[] a = new int[set.size()];
        int i = 0;
        for (Integer o : set)
            a[i++] = o;
        return a;
    }

    private ICompletionProposal[] finalizeListForIrAssistDisplay(ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;
        // #region agent log
        ContentAssistDebug.sessionLog("H4", "finalizeListForIrAssistDisplay", "path", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"irOrdering\":" + isIrAssistOrderingEnabled() //$NON-NLS-1$
                + ",\"hasIrSnapshot\":" + hasIrProposalsForCurrentContext() //$NON-NLS-1$
                + ",\"count\":" + raw.length + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (!isIrAssistOrderingEnabled())
        {
            if (hasIrProposalsForCurrentContext())
                return popupListWithIr(raw);
            return raw;
        }
        if (hasIrProposalsForCurrentContext())
            return popupListWithIr(raw);
        return buildDelegateOrderedList(sortListForIrAssist(unwrapProposals(raw), null));
    }

    /** Порядок delegate при smart-фильтре и пустом префиксе (штатный priority, не алфавит). */
    private ICompletionProposal[] resolveDelegateOrderedList(ITextViewer viewer, int offset,
                                                             int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (doc != null && caret >= 0)
        {
            int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
            if (dot >= 0)
            {
                ICompletionProposal[] member = resolveMemberAccessOrderedList(viewer, offset,
                    caret, dot);
                if (member.length > 0)
                    return finalizeListForIrAssistDisplay(member);
            }
        }

        scheduleEagerFullListLoad(viewer);
        if (fullListReady && fullListCache.length > 0)
        {
            ICompletionProposal[] built = finalizeListForIrAssistDisplay(fullListCache);
            if (built.length > 0)
            {
                lastStableDelegateList = built;
                return built;
            }
        }
        if (lastStableDelegateList.length > 0)
            return finalizeListForIrAssistDisplay(lastStableDelegateList);

        ICompletionProposal[] raw = fetchFullDelegateListAtAnchor(viewer, offset, caret);
        if (raw.length > 0)
        {
            ICompletionProposal[] built = finalizeListForIrAssistDisplay(raw);
            lastStableDelegateList = built;
            return built;
        }
        if (fullListCache.length > 0)
            return finalizeListForIrAssistDisplay(fullListCache);
        return EMPTY;
    }

    private ICompletionProposal[] fetchFullDelegateListAtAnchor(ITextViewer viewer, int offset,
                                                                int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int invokeOffset = caret >= 0 ? caret : offset;
        if (invokeOffset < 0)
            invokeOffset = offset;
        if (doc != null && caret >= 0
            && computeFullListContextKey(doc, caret) != fullListContextKey)
            return EMPTY;
        int dot = doc != null && caret >= 0
            ? ReceiverTypeLabel.findMemberAccessDot(doc, caret) : -1;
        if (dot >= 0)
        {
            ICompletionProposal[] member = resolveMemberAccessOrderedList(viewer, offset, caret,
                dot);
            if (member.length > 0)
            {
                assignFullListCache(unwrapProposals(member));
                fullListReady = true;
                if (member.length >= MIN_STABLE_MEMBER_CACHE)
                    fullListComplete = true;
                return member;
            }
        }
        ICompletionProposal[] nativeList =
            delegate.computeCompletionProposals(viewer, invokeOffset);
        ICompletionProposal[] raw = unwrapProposals(nativeList != null ? nativeList : EMPTY);
        if (raw.length > 0)
        {
            assignFullListCache(raw);
            fullListReady = true;
            fullListComplete = true;
        }
        return raw;
    }

    private ICompletionProposal[] fetchDelegateList(ITextViewer viewer, int probeOffset, int caret)
    {
        ICompletionProposal[] nativeList = delegate.computeCompletionProposals(viewer, probeOffset);
        if ((nativeList == null || nativeList.length == 0) && caret >= 0 && caret != probeOffset)
            nativeList = delegate.computeCompletionProposals(viewer, caret);
        return nativeList != null ? nativeList : EMPTY;
    }

    static int computeIdentifierWordStart(IDocument doc, int caret)
    {
        if (doc == null || caret < 0)
            return caret;
        String filter = computeIdentifierFilter(doc, caret);
        if (filter.isEmpty())
            return caret;
        return Math.max(0, caret - filter.length());
    }

    private static int resolveDelegateProbeOffset(ITextViewer viewer, int invocationOffset,
                                                  int caret)
    {
        if (caret >= 0)
            return caret;
        if (viewer != null && invocationOffset >= 0)
        {
            IDocument doc = viewer.getDocument();
            if (doc != null && invocationOffset <= doc.getLength())
                return invocationOffset;
        }
        return invocationOffset;
    }

    /** Сбрасывает таймер при каждом символе; {@link #loadFullList} — только после паузы. */
    void rescheduleIdleFullListLoad(ITextViewer viewer, boolean forceDelegateReadOnly)
    {
        if (viewer == null || fullListComplete)
            return;
        if (!SmartFilterTracker.getCurrentFilter().isEmpty())
        {
            cancelIdleFullListLoad();
            return;
        }
        org.eclipse.swt.widgets.Control widget = viewer.getTextWidget()
            instanceof org.eclipse.swt.widgets.Control
            ? (org.eclipse.swt.widgets.Control) viewer.getTextWidget()
            : null;
        if (widget == null || widget.isDisposed())
            return;
        org.eclipse.swt.widgets.Display display = widget.getDisplay();
        if (display == null || display.isDisposed())
            return;

        pendingIdleLoadReadOnly = forceDelegateReadOnly;
        if (pendingIdleLoadTask != null)
            display.timerExec(-1, pendingIdleLoadTask);

        final int contextKey = fullListContextKey;
        final boolean readOnly = forceDelegateReadOnly;
        pendingIdleLoadTask = () -> {
            pendingIdleLoadTask = null;
            if (widget.isDisposed() || fullListComplete || contextKey != fullListContextKey)
                return;
            if (!SmartFilterTracker.getCurrentFilter().isEmpty())
            {
                rescheduleIdleFullListLoad(viewer, readOnly);
                return;
            }
            loadFullList(viewer, readOnly);
            if (contextKey != fullListContextKey)
            {
                fullListReady = false;
                fullListComplete = false;
                assignFullListCache(EMPTY);
                return;
            }
            if (fullListReady && SmartFilterTracker.getCurrentFilter().isEmpty())
                ContentAssistSessionReloader.refreshPopupIfOpen();
        };
        display.timerExec(IDLE_FULL_LIST_MS, pendingIdleLoadTask);
    }

    private void cancelIdleFullListLoad()
    {
        if (pendingIdleLoadTask == null)
            return;
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        if (viewer != null && viewer.getTextWidget() instanceof org.eclipse.swt.widgets.Control)
        {
            org.eclipse.swt.widgets.Control widget = (org.eclipse.swt.widgets.Control) viewer.getTextWidget();
            if (!widget.isDisposed())
                widget.getDisplay().timerExec(-1, pendingIdleLoadTask);
        }
        pendingIdleLoadTask = null;
    }

    // ---- Точка вызова алгоритма для popup.validate / isValidFor ----------------

    private static final ThreadLocal<SmartCodeMatcher> VALIDATE_MATCHER = new ThreadLocal<>();
    private static final ThreadLocal<String> VALIDATE_FILTER = new ThreadLocal<>();

    static boolean proposalMatchesFilter(ICompletionProposal proposal, IDocument document,
                                         int offset, DocumentEvent event)
    {
        int caret = resolveFilterCaret(document, offset, event);
        boolean inLiteralValidate = isStringLiteralAssistContext(document, caret);
        // #region agent log
        if (ContentAssistDebug.shouldLogValidateLiteral())
            ContentAssistDebug.agentLog("H6", "proposalMatchesFilter", "enter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"caret\":" + caret + ",\"offset\":" + offset + ",\"inLiteral\":" + inLiteralValidate //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"smart\":" + SmartAssistFilterState.isSmartFilterEnabled() + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (inLiteralValidate)
            return matchesStockDelegateFilter(proposal, document, offset, event);
        if (!SmartAssistFilterState.isSmartFilterEnabled())
        {
            ICompletionProposal raw = unwrapProposal(proposal);
            if (raw instanceof IrCompletionProposal ir)
                return matchesIrStockPrefixFilter(ir, document, offset, event);
            return matchesStockDelegateFilter(proposal, document, offset, event);
        }
        if (isMemberAccessContextChange(document, event))
            return true;
        String filter = computeActiveFilter(document, offset, event);
        if (filter.isEmpty())
        {
            if (ReceiverTypeLabel.findMemberAccessDot(document, caret) >= 0
                && computeIdentifierFilter(document, caret).isEmpty())
                return true;
            String tracked = SmartFilterTracker.getCurrentFilter();
            if (!tracked.isEmpty())
                filter = tracked;
            else
                return true;
        }
        SmartCodeMatcher matcher = VALIDATE_MATCHER.get();
        if (matcher == null || !filter.equals(VALIDATE_FILTER.get()))
        {
            matcher = new SmartCodeMatcher(filter);
            VALIDATE_MATCHER.set(matcher);
            VALIDATE_FILTER.set(filter);
        }
        boolean ok = computeScore(matcher, proposal) > 0;
        return ok;
    }

    static void clearValidateMatcherCache()
    {
        VALIDATE_MATCHER.remove();
        VALIDATE_FILTER.remove();
    }

    /** Точка в документе или смена контекста — не сужать старый список через validate. */
    private static boolean isMemberAccessContextChange(IDocument document, DocumentEvent event)
    {
        if (document == null)
            return false;
        if (event != null && event.getText() != null && event.getText().indexOf('.') >= 0)
            return true;
        int caret = resolveFilterCaret(document, 0, event);
        return ReceiverTypeLabel.findMemberAccessDot(document, caret) >= 0
            && computeIdentifierFilter(document, caret).isEmpty();
    }

    // ---- Кэш полного списка ---------------------------------------------------

    /**
     * После точки (новый контекст свойств) — сброс кэша и перезапрос полного списка у delegate.
     */
    private void ensureFullListForContext(ITextViewer viewer, IDocument doc, int caret)
    {
        ensureFullListForContext(viewer, doc, caret, isPopupVisible());
    }

    private void ensureFullListForContext(ITextViewer viewer, IDocument doc, int caret,
                                          boolean allowSyncMemberCapture)
    {
        int contextKey = computeFullListContextKey(doc, caret);
        if (contextKey != fullListContextKey)
        {
            fullListContextKey = contextKey;
            fullListReady = false;
            fullListComplete = false;
            terminalEmptyMemberAccess = false;
            cachedNonObjectDot = -1;
            assignFullListCache(EMPTY);
            lastStableEmptyList = EMPTY;
            lastStableDelegateList = EMPTY;
            lastTrackedFilter = ""; //$NON-NLS-1$
            memberAccessReloadSeq++;
            memberAccessReloadScheduledSeq = -1;
            resetMemberStockFullList();
            cancelIdleFullListLoad();
            clearDelegateSyncProbe();
            ContentAssistDebug.log("fullList context change key=" + contextKey //$NON-NLS-1$
                + " caret=" + caret); //$NON-NLS-1$
            int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
            if (dot >= 0)
            {
                if (tryTerminalNonObjectMemberAccess(viewer, doc, caret) != null)
                    return;
                if (viewer != null)
                {
                    if (allowSyncMemberCapture && canShiftCaretForMemberCapture(viewer))
                        captureMemberStockAtDot(viewer, dot, true);
                    else
                        scheduleMemberStockCapture(viewer, dot);
                }
            }
            rescheduleIdleFullListLoad(viewer, false);
            return;
        }
        if (doc != null && caret >= 0 && !terminalEmptyMemberAccess)
            tryTerminalNonObjectMemberAccess(viewer, doc, caret);
    }

    private static int computeFullListContextKey(IDocument doc, int caret)
    {
        if (doc == null || caret < 0)
            return Integer.MIN_VALUE;
        int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
        if (dot >= 0)
            return dot;
        // Якорь = начало вводимого идентификатора; не меняется при наборе префикса
        int anchor = caret;
        String filter = computeIdentifierFilter(doc, caret);
        if (!filter.isEmpty())
            anchor = Math.max(0, caret - filter.length());
        return -(anchor + 1);
    }

    /**
     * При снятии smart-фильтра delegate перезапрашиваем только если кэш пустой
     * или подозрительно мал; иначе {@link #buildUnfilteredList} по уже загруженному кэшу.
     */
    private boolean shouldReloadDelegateOnUnfiltered()
    {
        if (terminalEmptyMemberAccess)
            return false;
        if (!fullListReady || fullListCache.length == 0)
            return true;
        return fullListCache.length < MIN_STABLE_MEMBER_CACHE;
    }

    private void loadFullList(ITextViewer viewer)
    {
        loadFullList(viewer, false);
    }

    private void loadFullList(ITextViewer viewer, boolean forceDelegateReadOnly)
    {
        int caret = resolveWidgetCaret(viewer);
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);
        int contextKey = computeFullListContextKey(doc, caret);
        if (contextKey != fullListContextKey)
            return;

        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        ICompletionProposal[] raw = loadDelegateProposals(viewer, doc, caret, dot, assistant,
            forceDelegateReadOnly);
        if (dot >= 0)
            raw = preferMemberFullList(viewer, dot, raw);
        if (raw == null || raw.length == 0)
        {
            if (tryTerminalNonObjectMemberAccess(viewer, doc, caret) != null)
                return;
            ContentAssistDebug.log("loadFullList EMPTY dot=" + dot + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + (forceDelegateReadOnly ? " readOnly" : "")); //$NON-NLS-1$ //$NON-NLS-2$
            fullListReady = false;
            if (dot >= 0 && shouldScheduleMemberReload(dot))
                scheduleMemberAccessReload(viewer, dot);
            return;
        }
        if (computeFullListContextKey(doc, caret) != fullListContextKey)
            return;
        assignFullListCache(unwrapProposals(raw));
        fullListReady = true;
        fullListComplete = true;
        ContentAssistDebug.log("loadFullList count=" + fullListCache.length //$NON-NLS-1$
            + " dot=" + dot + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
            + (forceDelegateReadOnly ? " readOnly" : "") + " complete"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (dot >= 0 && fullListCache.length < MIN_STABLE_MEMBER_CACHE
            && shouldScheduleMemberReload(dot))
            scheduleMemberAccessReload(viewer, dot);
    }

    /**
     * Загрузка member-access: быстрый probe без {@code readOnly} (не блокируем UI).
     * Догрузка — {@link #scheduleMemberAccessReload} на EDT, тоже без readOnly.
     */
    private ICompletionProposal[] loadDelegateProposals(ITextViewer viewer, IDocument doc,
                                                        int caret, int dot,
                                                        ContentAssistant assistant,
                                                        boolean forceDelegateReadOnly)
    {
        if (dot >= 0)
        {
            int[] offsets = memberAccessProbeOffsets(caret, dot, assistant, doc);
            if (forceDelegateReadOnly || !SmartAssistFilterState.isSmartFilterEnabled())
                return requestDelegateProposals(viewer, doc, caret, dot, assistant, offsets);
            return probeDelegateAtOffsets(viewer, offsets, dot);
        }
        int[] offsets = completionProbeOffsets(assistant, caret, dot, doc);
        if (forceDelegateReadOnly || !SmartAssistFilterState.isSmartFilterEnabled())
            return probeDelegateAtOffsets(viewer, offsets, dot);
        return requestDelegateProposals(viewer, doc, caret, dot, assistant, offsets);
    }

    private ICompletionProposal[] requestDelegateProposals(ITextViewer viewer, IDocument doc,
                                                          int caret, int dot,
                                                          ContentAssistant assistant)
    {
        int[] offsets = completionProbeOffsets(assistant, caret, dot, doc);
        return requestDelegateProposals(viewer, doc, caret, dot, assistant, offsets);
    }

    private ICompletionProposal[] requestDelegateProposals(ITextViewer viewer, IDocument doc,
                                                          int caret, int dot,
                                                          ContentAssistant assistant,
                                                          int[] offsets)
    {
        if (doc instanceof IXtextDocument)
        {
            try
            {
                return ((IXtextDocument) doc).readOnly(new IUnitOfWork<ICompletionProposal[], XtextResource>() {
                    @Override
                    public ICompletionProposal[] exec(XtextResource state) throws Exception
                    {
                        return probeDelegateAtOffsets(viewer, offsets, dot);
                    }
                });
            }
            catch (Exception e)
            {
                ContentAssistDebug.log("loadFullList readOnly ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return probeDelegateAtOffsets(viewer, offsets, dot);
    }

    private ICompletionProposal[] probeDelegateAtOffsets(ITextViewer viewer, int[] offsets,
                                                         int dot)
    {
        if (dot < 0)
        {
            ICompletionProposal[] best = EMPTY;
            for (int off : offsets)
            {
                if (off < 0)
                    continue;
                ICompletionProposal[] raw = delegate.computeCompletionProposals(viewer, off);
                int count = raw != null ? raw.length : 0;
                if (count == 0)
                    continue;
                if (count > best.length)
                    best = raw;
            }
            return best;
        }
        ICompletionProposal[] best = EMPTY;
        for (int off : offsets)
        {
            if (off < 0)
                continue;
            ICompletionProposal[] raw = delegate.computeCompletionProposals(viewer, off);
            int count = raw != null ? raw.length : 0;
            if (count == 0)
                continue;
            if (count > best.length)
                best = raw;
            if (best.length >= MIN_STABLE_MEMBER_CACHE)
                break;
        }
        return best;
    }

    private static int[] completionProbeOffsets(ContentAssistant assistant, int caret, int dot,
                                                IDocument doc)
    {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        if (dot < 0)
        {
            // Не probe по wordStart: replacementOffset в delegate привязан к offset вызова
            addProbeOffset(set, caret);
            if (assistant != null)
            {
                int inv = ContentAssistPopupSync.getInvocationOffset(assistant);
                if (inv >= 0)
                    set.add(inv);
            }
        }
        else
            return memberAccessProbeOffsets(caret, dot, assistant, doc);
        int[] a = new int[set.size()];
        int i = 0;
        for (Integer o : set)
            a[i++] = o;
        return a;
    }

    private static void addProbeOffset(java.util.LinkedHashSet<Integer> set, int offset)
    {
        if (offset >= 0)
            set.add(offset);
    }

    private static int[] memberAccessProbeOffsets(int caret, int dot, ContentAssistant assistant,
                                                  IDocument doc)
    {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        // Сразу после точки — полный member-access без префикса идентификатора
        addProbeOffset(set, dot + 1);
        if (doc != null)
        {
            String prefix = computeIdentifierFilter(doc, caret);
            if (!prefix.isEmpty())
            {
                int wordStart = caret - prefix.length();
                if (wordStart > dot)
                    addProbeOffset(set, wordStart);
            }
        }
        addProbeOffset(set, caret);
        if (assistant != null)
        {
            int popupOffset = ContentAssistPopupSync.getFilterOffset(assistant);
            if (popupOffset > dot)
                addProbeOffset(set, popupOffset);
        }
        int[] a = new int[set.size()];
        int i = 0;
        for (Integer o : set)
            a[i++] = o;
        return a;
    }

    private void resetMemberStockFullList()
    {
        memberStockFullList = EMPTY;
        memberStockFullListDot = -1;
    }

    private void captureMemberStockFullList(ICompletionProposal[] raw, int dot)
    {
        if (dot < 0 || raw == null || raw.length == 0)
            return;
        ICompletionProposal[] unwrapped = unwrapProposals(raw);
        if (memberStockFullListDot != dot)
        {
            memberStockFullListDot = dot;
            memberStockFullList = EMPTY;
        }
        if (unwrapped.length > memberStockFullList.length)
            memberStockFullList = unwrapped;
    }

    private ICompletionProposal[] preferMemberFullList(ITextViewer viewer, int dot,
                                                       ICompletionProposal[] raw)
    {
        if (terminalEmptyMemberAccess && cachedNonObjectDot == dot)
            return EMPTY;
        ICompletionProposal[] best = raw != null ? raw : EMPTY;
        if (memberStockFullListDot == dot && memberStockFullList.length > best.length)
            return memberStockFullList;
        if (best.length >= MIN_STABLE_MEMBER_CACHE)
            return best;
        boolean allowShift = canShiftCaretForMemberCapture(viewer);
        captureMemberStockAtDot(viewer, dot, allowShift);
        if (!allowShift && memberStockFullListDot != dot)
            scheduleMemberStockCapture(viewer, dot);
        if (memberStockFullListDot == dot && memberStockFullList.length > best.length)
            return memberStockFullList;
        return best;
    }

    /** Фоновая догрузка member-stock — не в hot path {@code computeCompletionProposals}. */
    private void scheduleMemberStockCapture(ITextViewer viewer, int dotContextKey)
    {
        if (viewer == null || dotContextKey < 0)
            return;
        if (memberStockFullListDot == dotContextKey
            && memberStockFullList.length >= MIN_STABLE_MEMBER_CACHE)
            return;
        try
        {
            if (viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
                return;
            org.eclipse.swt.widgets.Display display = viewer.getTextWidget().getDisplay();
            display.asyncExec(() -> runMemberStockCapture(viewer, dotContextKey, 0));
        }
        catch (Exception ignored) {}
    }

    private void runMemberStockCapture(ITextViewer viewer, int dotContextKey, int attempt)
    {
        try
        {
            if (fullListContextKey != dotContextKey)
                return;
            IDocument doc = viewer.getDocument();
            int caret = resolveWidgetCaret(viewer);
            if (ReceiverTypeLabel.findMemberAccessDot(doc, caret) != dotContextKey)
                return;
            boolean allowShift = canShiftCaretForMemberCapture(viewer);
            if (!allowShift && attempt < 8)
            {
                org.eclipse.swt.widgets.Display display = viewer.getTextWidget().getDisplay();
                if (display != null && !display.isDisposed())
                    display.timerExec(25, () -> runMemberStockCapture(viewer, dotContextKey,
                        attempt + 1));
                return;
            }
            int prev = memberStockFullListDot == dotContextKey ? memberStockFullList.length : 0;
            captureMemberStockAtDot(viewer, dotContextKey, allowShift);
            if (memberStockFullList.length > prev)
            {
                if (viewer instanceof SourceViewer)
                    ContentAssistPopupUi.updateContextTypeLabel((SourceViewer) viewer);
                ContentAssistSessionReloader.refreshPopupIfOpen();
            }
        }
        catch (Exception ignored) {}
    }

    private static boolean canShiftCaretForMemberCapture(ITextViewer viewer)
    {
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null)
            return true;
        return !ContentAssistPopupSync.isPopupVisible(assistant);
    }

    private ICompletionProposal[] resolveMemberAccessOrderedList(ITextViewer viewer, int offset,
                                                                 int caret, int dot)
    {
        if (terminalEmptyMemberAccess && cachedNonObjectDot == dot)
            return EMPTY;
        if (memberStockFullListDot == dot && memberStockFullList.length >= MIN_STABLE_MEMBER_CACHE)
        {
            // #region agent log
            ContentAssistDebug.sessionLog("H5", "resolveMemberAccess", "memberStock", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"dot\":" + dot + ",\"count\":" + memberStockFullList.length + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            return finalizeListForIrAssistDisplay(buildDelegateOrderedList(memberStockFullList));
        }

        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int[] offsets = memberAccessProbeOffsets(caret, dot, assistant, doc);
        ICompletionProposal[] probed = probeDelegateAtOffsets(viewer, offsets, dot);
        if (probed.length >= MIN_STABLE_MEMBER_CACHE)
        {
            captureMemberStockFullList(probed, dot);
            ICompletionProposal[] built = buildDelegateOrderedList(probed);
            lastStableDelegateList = built;
            return finalizeListForIrAssistDisplay(built);
        }

        if (canShiftCaretForMemberCapture(viewer))
        {
            captureMemberStockAtDot(viewer, dot, true);
            if (memberStockFullListDot == dot
                && memberStockFullList.length >= MIN_STABLE_MEMBER_CACHE)
            {
                ICompletionProposal[] built = buildDelegateOrderedList(memberStockFullList);
                lastStableDelegateList = built;
                return finalizeListForIrAssistDisplay(built);
            }
        }
        else
            scheduleMemberStockCapture(viewer, dot);

        if (probed.length > 0)
        {
            ICompletionProposal[] built = buildDelegateOrderedList(probed);
            lastStableDelegateList = built;
            return finalizeListForIrAssistDisplay(built);
        }
        return EMPTY;
    }

    /**
     * Кэш полного member-access: live probe при {@code ф.}, иначе краткий {@code setCaretOffset}
     * только в async-пути ({@code allowCaretShift=true}).
     */
    private ICompletionProposal[] captureMemberStockAtDot(ITextViewer viewer, int dot,
                                                          boolean allowCaretShift)
    {
        if (viewer == null || dot < 0)
            return EMPTY;
        if (memberStockFullListDot == dot && memberStockFullList.length >= MIN_STABLE_MEMBER_CACHE)
            return memberStockFullList;

        final int probeOffset = dot + 1;
        ICompletionProposal[] raw = requestMemberStockAtOffset(viewer, probeOffset, dot, false);
        if (raw.length >= MIN_STABLE_MEMBER_CACHE)
        {
            captureMemberStockFullList(raw, dot);
            return memberStockFullList;
        }
        if (allowCaretShift)
        {
            ICompletionProposal[] shifted = requestMemberStockAtOffset(viewer, probeOffset, dot,
                true);
            if (shifted.length > raw.length)
                raw = shifted;
            if (raw.length > 0)
                captureMemberStockFullList(raw, dot);
        }
        else if (raw.length > 0)
            captureMemberStockFullList(raw, dot);
        return memberStockFullListDot == dot ? memberStockFullList : raw;
    }

    private ICompletionProposal[] requestMemberStockAtOffset(ITextViewer viewer, int probeOffset,
                                                             int dot, boolean shiftCaret)
    {
        IDocument doc = viewer.getDocument();
        org.eclipse.swt.custom.StyledText widget = viewer.getTextWidget();
        int savedCaret = -1;
        try
        {
            if (shiftCaret && widget != null && !widget.isDisposed())
            {
                savedCaret = widget.getCaretOffset();
                widget.setCaretOffset(probeOffset);
            }
            ICompletionProposal[] raw;
            if (doc instanceof IXtextDocument)
            {
                raw = ((IXtextDocument) doc).readOnly(
                    new IUnitOfWork<ICompletionProposal[], XtextResource>()
                    {
                        @Override
                        public ICompletionProposal[] exec(XtextResource state) throws Exception
                        {
                            return delegate.computeCompletionProposals(viewer, probeOffset);
                        }
                    });
            }
            else
                raw = delegate.computeCompletionProposals(viewer, probeOffset);
            raw = unwrapProposals(raw != null ? raw : EMPTY);
            return raw;
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("captureMemberStockAtDot ERROR: " + e.getMessage()); //$NON-NLS-1$
            return EMPTY;
        }
        finally
        {
            if (savedCaret >= 0 && widget != null && !widget.isDisposed())
                widget.setCaretOffset(savedCaret);
        }
    }

    /** Повторная догрузка member-access только при маленьком кэше, без readOnly на UI. */
    private void scheduleMemberAccessReload(ITextViewer viewer, int dotContextKey)
    {
        if (viewer == null)
            return;
        if (!SmartFilterTracker.getCurrentFilter().isEmpty())
            return;
        if (!shouldScheduleMemberReload(dotContextKey))
            return;
        if (fullListCache.length >= MIN_STABLE_MEMBER_CACHE)
            return;
        if (memberAccessReloadScheduledSeq == memberAccessReloadSeq)
            return;
        memberAccessReloadScheduledSeq = memberAccessReloadSeq;
        try
        {
            if (viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
                return;
            org.eclipse.swt.widgets.Display display = viewer.getTextWidget().getDisplay();
            final int seq = memberAccessReloadSeq;
            int[] delaysMs = { 0, 50, 150, 400 };
            for (int delay : delaysMs)
            {
                display.timerExec(delay, () -> retryMemberAccessLoad(viewer, dotContextKey, seq));
            }
        }
        catch (Exception ignored) {}
    }

    private void retryMemberAccessLoad(ITextViewer viewer, int dotContextKey, int seq)
    {
        try
        {
            if (seq != memberAccessReloadSeq)
                return;
            if (!shouldScheduleMemberReload(dotContextKey))
                return;
            if (fullListContextKey != dotContextKey)
                return;
            if (fullListCache.length >= MIN_STABLE_MEMBER_CACHE)
                return;
            IDocument doc = viewer.getDocument();
            int caret = resolveWidgetCaret(viewer);
            if (ReceiverTypeLabel.findMemberAccessDot(doc, caret) != dotContextKey)
                return;
            int prev = fullListCache.length;
            ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
            int[] offsets = memberAccessProbeOffsets(caret, dotContextKey, assistant, doc);
            ICompletionProposal[] raw;
            if (!SmartAssistFilterState.isSmartFilterEnabled())
            {
                boolean allowShift = canShiftCaretForMemberCapture(viewer);
                captureMemberStockAtDot(viewer, dotContextKey, allowShift);
                if (!allowShift)
                    scheduleMemberStockCapture(viewer, dotContextKey);
                raw = memberStockFullListDot == dotContextKey ? memberStockFullList : EMPTY;
            }
            else
                raw = probeDelegateAtOffsets(viewer, offsets, dotContextKey);
            if (raw == null || raw.length <= prev)
                return;
            captureMemberStockFullList(raw, dotContextKey);
            ContentAssistDebug.log("loadFullList member-access retry count=" + raw.length //$NON-NLS-1$
                + " prev=" + prev + " dot=" + dotContextKey); //$NON-NLS-1$ //$NON-NLS-2$
            assignFullListCache(unwrapProposals(raw));
            fullListReady = true;
            if (fullListCache.length >= MIN_STABLE_MEMBER_CACHE)
            {
                fullListComplete = true;
                memberAccessReloadScheduledSeq = memberAccessReloadSeq;
            }
            ContentAssistSessionReloader.refreshPopupIfOpen();
        }
        catch (Exception ignored) {}
    }

    /**
     * Каретка на момент вызова assist: {@code invocationOffset} от ContentAssistant
     * (позиция Ctrl+Space). {@code StyledText.getCaretOffset()} на первом вызове часто
     * отстаёт от неё.
     */
    static int resolveInvocationCaret(ITextViewer viewer, int invocationOffset)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int widget = resolveWidgetCaret(viewer);
        if (invocationOffset >= 0 && widget >= 0)
            return clampCaret(doc, Math.max(invocationOffset, widget));
        if (invocationOffset >= 0)
            return clampCaret(doc, invocationOffset);
        return widget >= 0 ? widget : 0;
    }

    /** Каретка виджета — для refresh, смены каретки, idle-загрузки кэша. */
    static int resolveWidgetCaret(ITextViewer viewer)
    {
        if (viewer == null)
            return -1;
        try
        {
            Point sel = viewer.getSelectedRange();
            if (sel != null && sel.x >= 0)
                return sel.x + Math.max(0, sel.y);
            if (viewer.getTextWidget() != null)
            {
                int caret = viewer.getTextWidget().getCaretOffset();
                if (caret >= 0)
                    return caret;
            }
        }
        catch (Exception ignored) {}
        return -1;
    }

    static int resolveSessionCaret(ContentAssistant assistant, ITextViewer viewer)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int best = -1;
        int widget = resolveWidgetCaret(viewer);
        if (widget >= 0)
            best = widget;
        // Отключил грубую ошибку - брать старую позицию открытия окна автодополнения здесь нельзя!
//        if (assistant != null)
//        {
//            int completion = ContentAssistPopupSync.getLastCompletionOffset(assistant);
//            if (completion >= 0)
//                best = Math.max(best, completion);
//            int inv = ContentAssistPopupSync.getInvocationOffset(assistant);
//            if (inv >= 0)
//                best = Math.max(best, inv);
//        }
        return best >= 0 ? clampCaret(doc, best) : 0;
    }

    /**
     * Полный список при smart-фильтре и пустом префиксе: порядок delegate с индексами.
     */
    private ICompletionProposal[] buildDelegateOrderedList(ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;

        ICompletionProposal[] result = new ICompletionProposal[raw.length];
        for (int i = 0; i < raw.length; i++)
            result[i] = wrapProposal(unwrapProposal(raw[i]), i);
        return result;
    }

    /** Полный список при выключенном smart-фильтре: все элементы, алфавит (legacy). */
    private ICompletionProposal[] buildUnfilteredList(ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;

        List<ICompletionProposal> list = new ArrayList<>(raw.length);
        for (ICompletionProposal p : raw)
            list.add(unwrapProposal(p));
        list.sort((a, b) -> compareDisplayStrings(a, b));

        ICompletionProposal[] result = new ICompletionProposal[list.size()];
        for (int i = 0; i < list.size(); i++)
            result[i] = wrapProposal(list.get(i));
        return result;
    }

    /** Полный кэш, обёрнутый для штатного {@code validate} + {@link SmartCodeProposalSorter}. */
    private ICompletionProposal[] buildWrappedList(ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;

        ICompletionProposal[] result = new ICompletionProposal[raw.length];
        for (int i = 0; i < raw.length; i++)
            result[i] = wrapProposal(unwrapProposal(raw[i]));
        return result;
    }

    /** Штатный префиксный фильтр EDT: {@code validate} или {@code isValidFor} у delegate. */
    static boolean matchesStockDelegateFilter(ICompletionProposal proposal,
                                              IDocument document, int offset,
                                              DocumentEvent event)
    {
        ICompletionProposal raw = unwrapProposal(proposal);
        if (raw instanceof IrCompletionProposal ir)
            return matchesIrStockPrefixFilter(ir, document, offset, event);
        if (raw instanceof ICompletionProposalExtension2)
            return ((ICompletionProposalExtension2) raw).validate(document, offset, event);
        if (raw instanceof ICompletionProposalExtension)
            return ((ICompletionProposalExtension) raw).isValidFor(document, offset);
        return true;
    }

    /** Prefix match для ИР при выключенном smart-фильтре (без рекурсии через validate). */
    private static boolean matchesIrStockPrefixFilter(IrCompletionProposal ir, IDocument document,
        int offset, DocumentEvent event)
    {
        if (ir == null)
            return false;
        int caret = resolveFilterCaret(document, offset, event);
        String prefix = document != null && caret >= 0
            ? computeIdentifierFilter(document, caret) : ""; //$NON-NLS-1$
        if (prefix.isEmpty())
        {
            String tracked = SmartFilterTracker.getCurrentFilter();
            if (tracked != null && !tracked.isEmpty())
                prefix = tracked;
        }
        if (prefix.isEmpty())
            return true;
        String name = ir.getWordValue();
        if (name == null || name.isEmpty())
            name = ir.getFilterName();
        if (name == null || name.isEmpty())
            return false;
        return name.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** Штатный префиксный отбор для popup при выключенном smart-фильтре (без повторного {@code fFilterRunnable}). */
    static ICompletionProposal[] filterStockDelegateProposals(ITextViewer viewer, int caret,
                                                                ICompletionProposal[] raw)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (doc == null || caret < 0)
            return raw;
        List<ICompletionProposal> kept = new ArrayList<>(raw.length);
        for (ICompletionProposal p : raw)
        {
            if (matchesStockDelegateFilter(p, doc, caret, null))
                kept.add(p);
        }
        return kept.isEmpty() ? EMPTY : kept.toArray(new ICompletionProposal[kept.size()]);
    }

    private static ICompletionProposal wrapProposal(ICompletionProposal proposal)
    {
        return wrapProposal(proposal, -1);
    }

    private static ICompletionProposal wrapProposal(ICompletionProposal proposal, int delegateOrder)
    {
        if (proposal instanceof SmartCompletionProposal)
        {
            SmartCompletionProposal wrapped = (SmartCompletionProposal) proposal;
            if (delegateOrder < 0 || wrapped.getDelegateOrder() == delegateOrder)
                return proposal;
            return new SmartCompletionProposal(wrapped.getDelegate(), delegateOrder);
        }
        return new SmartCompletionProposal(proposal, delegateOrder);
    }

    /** Только для {@link #computeForPopupRefresh} — не для штатного keystroke path. */
    private ICompletionProposal[] filterAndSort(ICompletionProposal[] raw, String filter)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;

        SmartCodeMatcher matcher = new SmartCodeMatcher(filter);
        List<ICompletionProposal> filtered = new ArrayList<>(raw.length);

        for (ICompletionProposal p : raw)
        {
            if (computeScore(matcher, p) > 0)
                filtered.add(unwrapProposal(p));
        }

        if (filtered.isEmpty())
        {
            // #region agent log
            debugFilterDroppedAll(raw, filter, matcher);
            // #endregion
            return EMPTY;
        }

        Integer[] idx = new Integer[filtered.size()];
        for (int i = 0; i < idx.length; i++)
            idx[i] = i;

        Arrays.sort(idx, (a, b) -> compareProposals(matcher,
            filtered.get(a), filtered.get(b)));

        ICompletionProposal[] result = new ICompletionProposal[idx.length];
        for (int i = 0; i < idx.length; i++)
        {
            ICompletionProposal p = filtered.get(idx[i]);
            int order = delegateOrderOf(p);
            result[i] = wrapProposal(p, order >= 0 ? order : idx[i]);
        }
        logFilterAndSortExitThrottled(result);
        return result;
    }

    private static int filterAndSortLoggedOpenGen = -1;

    private void logFilterAndSortExitThrottled(ICompletionProposal[] result)
    {
        if (!"sessionOpen".equals(ContentAssistPopupSync.peekRecomputeTrigger())) //$NON-NLS-1$
            return;
        int openGen = ContentAssistSessionReloader.literalOpenGenForLog();
        if (openGen < 0 || openGen == filterAndSortLoggedOpenGen)
            return;
        filterAndSortLoggedOpenGen = openGen;
        String firstKey = result.length > 0
            ? ContentAssistDebug.firstProposalKey(result) : ""; //$NON-NLS-1$
        // #region agent log
        ContentAssistDebug.debugModeLog("H75", "filterAndSort", "exit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"trigger\":\"sessionOpen\"" //$NON-NLS-1$
                + ",\"resultN\":" + result.length //$NON-NLS-1$
                + ",\"firstKey\":\"" + firstKey + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"smartWrapped\":true" //$NON-NLS-1$
                + ",\"openGen\":" + openGen //$NON-NLS-1$
                + ",\"msSinceSessionStart\":" //$NON-NLS-1$
                    + ContentAssistSessionReloader.msSinceLiteralSessionStartForLog()
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    static int computeScore(SmartCodeMatcher matcher, ICompletionProposal proposal)
    {
        if (matcher.isEmpty)
            return 1;
        String display = displayString(unwrapProposal(proposal));
        if (display == null || display.isEmpty())
            return 0;
        int name = matcher.computeNamePremium(display);
        if (name <= 0)
            return 0;
        return name * NAME_WEIGHT + matcher.computeParamPremium(display) * PARAM_WEIGHT;
    }

    static ICompletionProposal unwrapProposal(ICompletionProposal proposal)
    {
        while (proposal instanceof SmartCompletionProposal)
            proposal = ((SmartCompletionProposal) proposal).getDelegate();
        return proposal;
    }

    private static ICompletionProposal[] unwrapProposals(ICompletionProposal[] raw)
    {
        ICompletionProposal[] result = new ICompletionProposal[raw.length];
        for (int i = 0; i < raw.length; i++)
            result[i] = unwrapProposal(raw[i]);
        return result;
    }

    public static int compareProposals(SmartCodeMatcher matcher,
                                       ICompletionProposal p1,
                                       ICompletionProposal p2)
    {
        int n1 = computeNameScore(matcher, p1);
        int n2 = computeNameScore(matcher, p2);
        if (n1 != n2)
            return Integer.compare(n2, n1);

        int o1 = delegateOrderOf(p1);
        int o2 = delegateOrderOf(p2);
        if (o1 >= 0 && o2 >= 0)
            return Integer.compare(o1, o2);

        int pr1 = resolveNativePriority(p1);
        int pr2 = resolveNativePriority(p2);
        if (pr1 != pr2)
            return Integer.compare(pr2, pr1);

        if (matcher.isEmpty)
            return compareDelegateOrder(p1, p2);
        return compareDisplayStrings(p1, p2);
    }

    static int computeNameScore(SmartCodeMatcher matcher, ICompletionProposal proposal)
    {
        if (matcher.isEmpty)
            return 1;
        String display = displayString(unwrapProposal(proposal));
        if (display == null || display.isEmpty())
            return 0;
        int name = matcher.computeNamePremium(display);
        return name <= 0 ? 0 : name * NAME_WEIGHT;
    }

    static int resolveNativePriority(ICompletionProposal proposal)
    {
        ICompletionProposal p = unwrapProposal(proposal);
        if (p instanceof IrCompletionProposal ir)
            return ir.getIrPriority();
        if (p instanceof ConfigurableCompletionProposal)
            return ((ConfigurableCompletionProposal) p).getPriority();
        return Integer.MIN_VALUE;
    }

    // #region agent log
    private static void debugSessionOrderSample(String hypothesisId, String location, String message,
                                                ICompletionProposal[] list, int max)
    {
        if (list == null || list.length == 0)
        {
            ContentAssistDebug.sessionLog(hypothesisId, location, message, "{\"count\":0}"); //$NON-NLS-1$
            return;
        }
        int n = Math.min(max, list.length);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"count\":").append(list.length).append(",\"items\":["); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < n; i++)
        {
            if (i > 0)
                sb.append(',');
            ICompletionProposal raw = unwrapProposal(list[i]);
            String name = displayString(raw);
            if (name == null)
                name = ""; //$NON-NLS-1$
            if (name.length() > 40)
                name = name.substring(0, 40);
            sb.append("{\"i\":").append(i) //$NON-NLS-1$
                .append(",\"name\":\"").append(escapeJson(name)).append('"') //$NON-NLS-1$
                .append(",\"pri\":").append(resolveNativePriority(raw)) //$NON-NLS-1$
                .append(",\"ir\":").append(raw instanceof IrCompletionProposal) //$NON-NLS-1$
                .append('}');
        }
        sb.append("]}"); //$NON-NLS-1$
        ContentAssistDebug.sessionLog(hypothesisId, location, message, sb.toString());
    }

    private static String escapeJson(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean isIrMergeDebugKey(String key)
    {
        if (key == null || key.isEmpty())
            return false;
        String lower = key.toLowerCase();
        return "наименование".equals(lower) //$NON-NLS-1$
            || "наименованиеполное".equals(lower) //$NON-NLS-1$
            || "ссылка".equals(lower) //$NON-NLS-1$
            || "найтиследующий".equals(lower) //$NON-NLS-1$
            || "количество".equals(lower); //$NON-NLS-1$
    }

    private static void debugIrMergeOrderSample(String hypothesisId, String location, String phase,
                                                ICompletionProposal[] list,
                                                Map<String, Integer> irPriorityByKey,
                                                MemberEdtPriorityProfile profile)
    {
        if (list == null || list.length == 0)
            return;
        if (profile == null)
            profile = MemberEdtPriorityProfile.NONE;
        Map<String, Integer> irMap = irPriorityByKey != null
            ? irPriorityByKey
            : java.util.Collections.emptyMap();
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"phase\":\"").append(escapeJson(phase)).append("\",\"remap\":") //$NON-NLS-1$ //$NON-NLS-2$
            .append(profile.isRemapActive())
            .append(",\"edtSortOn\":").append(useEdtSortKey(irMap)) //$NON-NLS-1$
            .append(",\"methDom\":").append(profile.methodDominantValue()) //$NON-NLS-1$
            .append(",\"propDom\":").append(profile.propertyDominantValue()) //$NON-NLS-1$
            .append(",\"items\":["); //$NON-NLS-1$
        boolean any = false;
        for (int i = 0; i < list.length; i++)
        {
            String key = dedupKey(list[i]);
            if (!isIrMergeDebugKey(key))
                continue;
            if (any)
                sb.append(',');
            any = true;
            ICompletionProposal raw = unwrapProposal(list[i]);
            sb.append("{\"i\":").append(i) //$NON-NLS-1$
                .append(",\"key\":\"").append(escapeJson(key)).append('"') //$NON-NLS-1$
                .append(",\"cls\":\"").append(raw.getClass().getSimpleName()).append('"') //$NON-NLS-1$
                .append(",\"irMerge\":").append(resolveIrPriorityForMerge(list[i], irMap)) //$NON-NLS-1$
                .append(",\"edtSort\":").append(profile.effectiveEdtForIrSort(list[i])) //$NON-NLS-1$
                .append(",\"mapPri\":").append(irMap.getOrDefault(key, -99999)).append('}'); //$NON-NLS-1$
        }
        sb.append("]}"); //$NON-NLS-1$
        ContentAssistDebug.sessionLog(hypothesisId, location, phase, sb.toString());
    }

    private void debugResolveExit(IDocument doc, int caret, String filter, int interimN,
        boolean cacheValid, String exit, ICompletionProposal[] result)
    {
        String prefix = doc != null && caret >= 0 ? computeIdentifierFilter(doc, caret) : ""; //$NON-NLS-1$
        ContentAssistDebug.debugModeLog("H48", "resolveProposalList", "resolveExit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"filter\":\"" + ContentAssistDebug.jsonEscapeForLog(filter != null ? filter : "") //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"prefix\":\"" + ContentAssistDebug.jsonEscapeForLog(prefix) //$NON-NLS-1$
                + "\",\"cacheValid\":" + cacheValid //$NON-NLS-1$
                + ",\"cacheN\":" + fullListCache.length //$NON-NLS-1$
                + ",\"interimN\":" + interimN //$NON-NLS-1$
                + ",\"delegateProbed\":" + isDelegateSyncProbedForContext() //$NON-NLS-1$
                + ",\"irResolved\":" + isIrWordsResolvedForContext() //$NON-NLS-1$
                + ",\"irN\":" + irProposals.length //$NON-NLS-1$
                + ",\"ctxKey\":" + fullListContextKey //$NON-NLS-1$
                + ",\"irCtxKey\":" + irSnapshotContextKey //$NON-NLS-1$
                + ",\"terminalEmpty\":" + terminalEmptyMemberAccess //$NON-NLS-1$
                + ",\"resultN\":" + (result != null ? result.length : 0) //$NON-NLS-1$
                + ",\"exit\":\"" + exit + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void debugFilterCachedExit(ITextViewer viewer, int caret, String filter, IDocument doc,
        int interimN, String path, ICompletionProposal[] result)
    {
        String prefix = doc != null && caret >= 0 ? computeIdentifierFilter(doc, caret) : ""; //$NON-NLS-1$
        ContentAssistDebug.debugModeLog("H49", "filterCachedProposalsForPopup", "filterCachedExit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"filter\":\"" + ContentAssistDebug.jsonEscapeForLog(filter != null ? filter : "") //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"prefix\":\"" + ContentAssistDebug.jsonEscapeForLog(prefix) //$NON-NLS-1$
                + "\",\"cacheValid\":" + isCacheValidForCaret(doc, caret) //$NON-NLS-1$
                + ",\"cacheN\":" + fullListCache.length //$NON-NLS-1$
                + ",\"interimN\":" + interimN //$NON-NLS-1$
                + ",\"delegateProbed\":" + isDelegateSyncProbedForContext() //$NON-NLS-1$
                + ",\"irResolved\":" + isIrWordsResolvedForContext() //$NON-NLS-1$
                + ",\"irN\":" + irProposals.length //$NON-NLS-1$
                + ",\"ctxKey\":" + fullListContextKey //$NON-NLS-1$
                + ",\"irCtxKey\":" + irSnapshotContextKey //$NON-NLS-1$
                + ",\"terminalEmpty\":" + terminalEmptyMemberAccess //$NON-NLS-1$
                + ",\"resultN\":" + (result != null ? result.length : 0) //$NON-NLS-1$
                + ",\"path\":\"" + path + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void debugFilterDroppedAll(ICompletionProposal[] raw, String filter,
        SmartCodeMatcher matcher)
    {
        StringBuilder sb = new StringBuilder(384);
        sb.append("{\"filter\":\"").append(ContentAssistDebug.jsonEscapeForLog(filter != null ? filter : "")) //$NON-NLS-1$ //$NON-NLS-2$
            .append("\",\"rawN\":").append(raw.length).append(",\"samples\":["); //$NON-NLS-1$ //$NON-NLS-2$
        int n = Math.min(3, raw.length);
        for (int i = 0; i < n; i++)
        {
            if (i > 0)
                sb.append(',');
            ICompletionProposal p = unwrapProposal(raw[i]);
            String display = displayString(p);
            if (display == null)
                display = ""; //$NON-NLS-1$
            if (display.length() > 48)
                display = display.substring(0, 48);
            sb.append("{\"display\":\"").append(escapeJson(display)).append('"') //$NON-NLS-1$
                .append(",\"score\":").append(computeScore(matcher, raw[i])) //$NON-NLS-1$
                .append(",\"isIr\":").append(p instanceof IrCompletionProposal).append('}'); //$NON-NLS-1$
        }
        sb.append("]}"); //$NON-NLS-1$
        ContentAssistDebug.debugModeLog("H51", "filterAndSort", "filterDroppedAll", sb.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
    // #endregion

    private static int compareDelegateOrder(ICompletionProposal p1, ICompletionProposal p2)
    {
        int o1 = delegateOrderOf(p1);
        int o2 = delegateOrderOf(p2);
        if (o1 >= 0 && o2 >= 0)
            return Integer.compare(o1, o2);
        return compareDisplayStrings(p1, p2);
    }

    private static int delegateOrderOf(ICompletionProposal proposal)
    {
        if (proposal == null)
            return -1;
        for (ICompletionProposal cur = proposal; cur instanceof SmartCompletionProposal; )
        {
            SmartCompletionProposal wrapped = (SmartCompletionProposal) cur;
            int order = wrapped.getDelegateOrder();
            if (order >= 0)
                return order;
            cur = wrapped.getDelegate();
        }
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor != null)
            return processor.lookupDelegateOrder(proposal);
        return -1;
    }

    private static int compareDisplayStrings(ICompletionProposal p1, ICompletionProposal p2)
    {
        String d1 = displayString(p1);
        String d2 = displayString(p2);
        if (d1 == null) return d2 == null ? 0 : 1;
        if (d2 == null) return -1;
        return d1.compareToIgnoreCase(d2);
    }

    static String displayString(ICompletionProposal p)
    {
        return (p == null) ? null : p.getDisplayString();
    }

    /** Префикс идентификатора только слева от каретки (не захватывает текст справа). */
    static String computeIdentifierFilter(IDocument doc, int offset)
    {
        try
        {
            if (doc == null || offset < 0)
                return ""; //$NON-NLS-1$
            int start = offset;
            while (start > 0 && isFilterChar(doc.getChar(start - 1)))
                start--;
            if (start < offset)
                return doc.get(start, offset - start);
            return ""; //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            return ""; //$NON-NLS-1$
        }
    }

    static String computeActiveFilter(IDocument doc, int offset, DocumentEvent event)
    {
        return computeIdentifierFilter(doc, resolveFilterCaret(doc, offset, event));
    }

    static int resolveFilterCaret(IDocument doc, int offset, DocumentEvent event)
    {
        // offset из popup.validate — это fFilterOffset (начало слова), не каретка
        int caret = resolveFilterCaretPreferWidget(doc);
        if (caret >= 0)
            return caret;

        if (event != null)
        {
            try
            {
                String text = event.getText();
                int eventEnd = event.getOffset() + (text != null ? text.length() : 0);
                return clampCaret(doc, eventEnd);
            }
            catch (Exception ignored) {}
        }

        if (doc != null && offset >= 0)
        {
            String tracked = SmartFilterTracker.getCurrentFilter();
            if (!tracked.isEmpty())
            {
                int candidate = offset + tracked.length();
                if (candidate <= doc.getLength()
                    && tracked.equals(computeIdentifierFilter(doc, candidate)))
                    return candidate;
            }
            String atOffset = computeIdentifierFilter(doc, offset);
            if (!atOffset.isEmpty())
                return clampCaret(doc, offset + atOffset.length());
            return clampCaret(doc, offset);
        }
        return offset;
    }

    private static int resolveFilterCaretPreferWidget(IDocument doc)
    {
        org.eclipse.jface.text.source.ISourceViewer active =
            ContentAssistSessionReloader.getActiveViewer();
        if (active != null)
        {
            int widgetCaret = resolveWidgetCaret(active);
            if (widgetCaret >= 0)
                return clampCaret(doc, widgetCaret);
        }
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        if (assistant != null)
        {
            int completionOff = ContentAssistPopupSync.getLastCompletionOffset(assistant);
            if (completionOff >= 0)
                return clampCaret(doc, completionOff);
        }
        Integer last = LAST_COMPUTE_CARET.get();
        if (last != null && last >= 0)
            return clampCaret(doc, last);
        return -1;
    }

    private static int clampCaret(IDocument doc, int caret)
    {
        if (doc == null || caret < 0)
            return caret;
        int len = doc.getLength();
        return caret > len ? len : caret;
    }

    static boolean isFilterChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_' || c == '&' || c == '~';
    }

    /** Закрыть assist, если в документ вставлен символ вне {@link #isFilterChar}. */
    static boolean shouldCloseAssistOnDocumentEvent(DocumentEvent event)
    {
        if (event == null)
            return false;
        String text = event.getText();
        if (text == null || text.isEmpty())
            return false;
        // Точка после идентификатора — member-access, popup не закрываем
        if (".".equals(text)) //$NON-NLS-1$
            return false;
        for (int i = 0; i < text.length(); i++)
        {
            if (!isFilterChar(text.charAt(i)))
                return true;
        }
        return false;
    }

    @Override
    public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset)
    {
        return delegate.computeContextInformation(viewer, offset);
    }

    @Override
    public char[] getCompletionProposalAutoActivationCharacters()
    {
        if (activationChars != null)
            return activationChars.toCharArray();
        return delegate.getCompletionProposalAutoActivationCharacters();
    }

    @Override
    public char[] getContextInformationAutoActivationCharacters()
    {
        return delegate.getContextInformationAutoActivationCharacters();
    }

    @Override
    public String getErrorMessage()
    {
        return delegate.getErrorMessage();
    }

    @Override
    public IContextInformationValidator getContextInformationValidator()
    {
        return delegate.getContextInformationValidator();
    }

    // ---- Вложенные утилиты content assist -------------------------------------

    /** Ctrl+Space → {@code handleRepeatedInvocation} в {@code CompletionProposalPopup}. */
    private static final class RepeatedInvocationDetect
    {
        private RepeatedInvocationDetect() {}

        static boolean isActive()
        {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace())
            {
                if (!"handleRepeatedInvocation".equals(frame.getMethodName())) //$NON-NLS-1$
                    continue;
                String cn = frame.getClassName();
                if (cn != null && cn.contains("CompletionProposalPopup")) //$NON-NLS-1$
                    return true;
            }
            return false;
        }
    }

    /** Краткая подсказка по stack для H62 (JFace vs Xtext/EDT repeat). */
    static String repeatInvocationStackHint()
    {
        boolean jfaceRepeat = false;
        boolean xtextRepeat = false;
        boolean bslRepeat = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace())
        {
            String cn = frame.getClassName();
            if (cn == null)
                continue;
            if ("handleRepeatedInvocation".equals(frame.getMethodName()) //$NON-NLS-1$
                && cn.contains("CompletionProposalPopup")) //$NON-NLS-1$
                jfaceRepeat = true;
            if (cn.contains("RepeatedContentAssistProcessor")) //$NON-NLS-1$
                xtextRepeat = true;
            if (cn.contains("BslRepeatedContentAssistProcessor")) //$NON-NLS-1$
                bslRepeat = true;
        }
        if (jfaceRepeat)
            return "jfaceRepeat"; //$NON-NLS-1$
        if (bslRepeat)
            return "bslRepeated"; //$NON-NLS-1$
        if (xtextRepeat)
            return "xtextRepeated"; //$NON-NLS-1$
        return "none"; //$NON-NLS-1$
    }

    private static int countEdtConfigurableProposals(ICompletionProposal[] proposals)
    {
        if (proposals == null || proposals.length == 0)
            return 0;
        int n = 0;
        for (ICompletionProposal proposal : proposals)
        {
            ICompletionProposal raw = unwrapProposal(proposal);
            if (raw == null)
                continue;
            String cn = raw.getClass().getName();
            if (cn.contains("BslConfigurable") || cn.contains("EventCompletionProposal")) //$NON-NLS-1$ //$NON-NLS-2$
                n++;
        }
        return n;
    }

    /** Ручной {@code showPossibleCompletions} (Ctrl+Space), не auto-activation. */
    private static final class ManualInvocationDetect
    {
        private ManualInvocationDetect() {}

        static boolean isActive()
        {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            boolean inShow = false;
            for (StackTraceElement frame : stack)
            {
                if ("showPossibleCompletions".equals(frame.getMethodName())) //$NON-NLS-1$
                {
                    String cn = frame.getClassName();
                    if (cn != null && cn.contains("ContentAssistant")) //$NON-NLS-1$
                        inShow = true;
                }
                if (inShow && "computeCompletionProposals".equals(frame.getMethodName())) //$NON-NLS-1$
                    return true;
            }
            return false;
        }
    }

    private static BslXtextEditor resolveEditorForLiteralAudit(ITextViewer viewer)
    {
        ContentAssistSessionReloader reloader = viewer instanceof SourceViewer sv
            ? ContentAssistSessionReloader.forViewer(sv)
            : ContentAssistSessionReloader.getActiveReloader();
        return reloader != null ? reloader.getBslEditor() : null;
    }

    private static void auditLiteralComputeReturn(ITextViewer viewer, BslXtextEditor editor,
        int returnedN, String computePhase, String extraReason)
    {
        if (!(viewer instanceof SourceViewer sv))
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.forViewer(sv);
        SmartContentAssistProcessor proc = reloader != null
            ? reloader.getProcessor() : ContentAssistSessionReloader.getActiveProcessor();
        if (proc == null)
            proc = ContentAssistSessionReloader.getActiveProcessor();
        if (proc == null)
            return;
        ContentAssistPopupSync.auditLiteralComputeReturn(sv, editor, proc, returnedN,
            computePhase, extraReason);
    }

    /** Тип выражения слева от точки ({@code obj.|}). */
    public static final class ReceiverTypeLabel
    {
        private ReceiverTypeLabel() {}

        public static String resolve(ISourceViewer viewer)
        {
            if (viewer == null)
                return ""; //$NON-NLS-1$
            IDocument doc = viewer.getDocument();
            if (doc == null)
                return ""; //$NON-NLS-1$

            int caret = resolveCaretOffset(viewer);
            if (caret < 0)
                return ""; //$NON-NLS-1$

            int dotOffset = findMemberAccessDot(doc, caret);
            if (dotOffset < 0)
                return ""; //$NON-NLS-1$

            if (doc instanceof IXtextDocument)
            {
                try
                {
                    IXtextDocument xtextDoc = (IXtextDocument) doc;
                    String fromModel = xtextDoc.readOnly(new IUnitOfWork<String, XtextResource>() {
                        @Override
                        public String exec(XtextResource resource) throws Exception
                        {
                            return resolveFromResource(resource, dotOffset, caret);
                        }
                    });
                    if (fromModel != null && !fromModel.isEmpty())
                        return fromModel;
                }
                catch (Exception e)
                {
                    ContentAssistDebug.log("receiverType ERROR: " + e.getMessage()); //$NON-NLS-1$
                }
            }

            return readReceiverExpressionText(doc, dotOffset);
        }

        static int findMemberAccessDot(IDocument doc, int caret)
        {
            try
            {
                int pos = caret;
                while (pos > 0)
                {
                    char ch = doc.getChar(pos - 1);
                    if (ch == ')' || ch == '(')
                        pos--;
                    else if (isFilterChar(ch))
                        pos--;
                    else
                        break;
                }
                if (pos <= 0 || doc.getChar(pos - 1) != '.')
                    return -1;
                return pos - 1;
            }
            catch (Exception ignored)
            {
                return -1;
            }
        }

        private static final java.util.Set<String> NON_OBJECT_TYPE_NAMES = java.util.Set.of(
            "Число", "Строка", "Булево", "Дата", "Неопределено", //$NON-NLS-1$
            "Number", "String", "Boolean", "Date", "Undefined", "Null"); //$NON-NLS-1$

        /**
         * Приёмник member-access — примитив / не-объект (нет свойств после точки).
         */
        static boolean isNonObjectReceiver(IDocument doc, int dotOffset, int caret)
        {
            if (!(doc instanceof IXtextDocument))
                return false;
            try
            {
                Boolean nonObject = ((IXtextDocument) doc).readOnly(
                    new IUnitOfWork<Boolean, XtextResource>()
                    {
                        @Override
                        public Boolean exec(XtextResource resource) throws Exception
                        {
                            Expression receiver = findReceiverExpression(resource, dotOffset,
                                caret);
                            if (receiver == null || receiver.getTypes().isEmpty())
                                return Boolean.FALSE;
                            for (TypeItem type : receiver.getTypes())
                            {
                                if (type == null)
                                    continue;
                                if (!isNonObjectTypeName(formatTypeItem(type)))
                                    return Boolean.FALSE;
                            }
                            return Boolean.TRUE;
                        }
                    });
                return Boolean.TRUE.equals(nonObject);
            }
            catch (Exception e)
            {
                ContentAssistDebug.log("isNonObjectReceiver ERROR: " + e.getMessage()); //$NON-NLS-1$
                return false;
            }
        }

        private static boolean isNonObjectTypeName(String name)
        {
            if (name == null || name.isEmpty())
                return false;
            String trimmed = name.trim();
            for (String known : NON_OBJECT_TYPE_NAMES)
            {
                if (known.equalsIgnoreCase(trimmed))
                    return true;
            }
            return false;
        }

        private static String resolveFromResource(XtextResource resource, int dotOffset,
                                                int completionOffset)
        {
            Expression receiver = findReceiverExpression(resource, dotOffset, completionOffset);
            if (receiver == null)
                return ""; //$NON-NLS-1$
            String types = formatExpressionTypes(receiver);
            if (!types.isEmpty())
                return types;
            return formatExpressionSourceText(receiver);
        }

        private static int resolveCaretOffset(ISourceViewer viewer)
        {
            try
            {
                if (viewer.getTextWidget() != null)
                {
                    int caret = viewer.getTextWidget().getCaretOffset();
                    if (caret >= 0)
                        return caret;
                }
                Point sel = viewer.getSelectedRange();
                if (sel != null && sel.x >= 0)
                    return sel.x + Math.max(0, sel.y);
            }
            catch (Exception ignored) {}
            return -1;
        }

        /**
         * Находит выражение-источник (receiver) слева от точки в позиции dotOffset.
         *
         * <p>Алгоритм:
         * <ol>
         *   <li>Ищем листовую ноду на позиции точки.</li>
         *   <li>Поднимаемся по семантическому дереву от найденного элемента.</li>
         *   <li>Ищем ближайший {@link DynamicFeatureAccess}, у которого source-поддерево
         *       заканчивается не позже dotOffset — это и есть receiver.</li>
         *   <li>Если receiver не найден через node-модель, пробуем через
         *       {@link EObjectAtOffsetHelper} как запасной вариант.</li>
         * </ol>
         */
        private static Expression findReceiverExpression(XtextResource resource, int dotOffset,
                                                       int completionOffset)
        {
            // Основной путь: через ноду точки поднимаемся по семантическому дереву
            INode dotNode = findNodeAtOffset(resource, dotOffset);
            if (dotNode != null)
            {
                Expression receiver = findReceiverFromNode(dotNode, dotOffset);
                if (receiver != null)
                    return receiver;
            }

            // Запасной путь: через EObjectAtOffsetHelper
            EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
            for (int probe : new int[] { dotOffset, dotOffset - 1, completionOffset - 1 })
            {
                if (probe < 0)
                    continue;
                EObject at = helper.resolveElementAt(resource, probe);
                if (at == null)
                    continue;
                Expression receiver = findReceiverInAncestors(at, dotOffset);
                if (receiver != null)
                    return receiver;
            }
            return null;
        }

        /**
         * Поднимается по цепочке семантических предков, начиная с семантического элемента
         * листа {@code dotNode}, и ищет ближайший {@link DynamicFeatureAccess},
         * чей source-поддерево заканчивается до dotOffset включительно.
         */
        private static Expression findReceiverFromNode(INode dotNode, int dotOffset)
        {
            EObject startSemantic = dotNode.getSemanticElement();
            if (startSemantic == null)
            {
                // Пробуем родителей ноды
                for (INode n = dotNode.getParent(); n != null; n = n.getParent())
                {
                    startSemantic = n.getSemanticElement();
                    if (startSemantic != null)
                        break;
                }
            }
            if (startSemantic == null)
                return null;
            return findReceiverInAncestors(startSemantic, dotOffset);
        }

        /**
         * Перебирает element и его контейнеры снизу вверх.
         * Возвращает source первого {@link DynamicFeatureAccess}, у которого точка
         * действительно стоит после source-поддерева.
         */
        private static Expression findReceiverInAncestors(EObject element, int dotOffset)
        {
            for (EObject e = element; e != null; e = e.eContainer())
            {
                if (e instanceof DynamicFeatureAccess)
                {
                    DynamicFeatureAccess access = (DynamicFeatureAccess) e;
                    Expression source = access.getSource();
                    if (source == null)
                        continue;
                    // Точка должна стоять после конца source-поддерева
                    if (sourceEndsBeforeDot(source, dotOffset))
                        return source;
                }
            }
            return null;
        }

        /**
         * Возвращает true, если нода выражения {@code source} заканчивается
         * не позже позиции точки (т.е. точка стоит после source, а не внутри него).
         */
        private static boolean sourceEndsBeforeDot(Expression source, int dotOffset)
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(source);
            if (node == null)
                return false; // не знаем — не рискуем
            return node.getEndOffset() <= dotOffset;
        }

        private static INode findNodeAtOffset(XtextResource resource, int offset)
        {
            if (resource == null || resource.getParseResult() == null)
                return null;
            ICompositeNode root = resource.getParseResult().getRootNode();
            if (root == null)
                return null;
            INode at = NodeModelUtils.findLeafNodeAtOffset(root, offset);
            if (at != null)
                return at;
            if (offset > 0)
                return NodeModelUtils.findLeafNodeAtOffset(root, offset - 1);
            return null;
        }

        private static String formatExpressionTypes(Expression expression)
        {
            if (expression == null || expression.getTypes().isEmpty())
                return ""; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            for (TypeItem type : expression.getTypes())
            {
                if (type == null)
                    continue;
                if (sb.length() > 0)
                    sb.append(", "); //$NON-NLS-1$
                sb.append(formatTypeItem(type));
            }
            return sb.toString();
        }

        private static String formatExpressionSourceText(Expression expression)
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(expression);
            if (node == null)
                return ""; //$NON-NLS-1$
            String text = NodeModelUtils.getTokenText(node);
            return text != null ? text.trim() : ""; //$NON-NLS-1$
        }

        private static String readReceiverExpressionText(IDocument doc, int dotOffset)
        {
            try
            {
                int start = dotOffset;
                while (start > 0 && isReceiverTextChar(doc.getChar(start - 1)))
                    start--;
                if (start >= dotOffset)
                    return ""; //$NON-NLS-1$
                return doc.get(start, dotOffset - start).trim();
            }
            catch (Exception ignored)
            {
                return ""; //$NON-NLS-1$
            }
        }

        private static boolean isReceiverTextChar(char c)
        {
            return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ']' || c == ')';
        }

        private static String formatTypeItem(TypeItem type)
        {
            if (type instanceof DuallyNamedElement)
            {
                String ru = ((DuallyNamedElement) type).getNameRu();
                if (ru != null && !ru.isEmpty())
                    return ru;
            }
            String name = type.getName();
            return name != null ? name : type.toString();
        }
    }
}
