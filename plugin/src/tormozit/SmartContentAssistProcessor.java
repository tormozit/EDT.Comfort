package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
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
    /** Пауза без ввода и с пустым фильтром перед тяжёлой {@link #loadFullList}. */
    private static final int IDLE_FULL_LIST_MS = 1500;
    private Runnable pendingIdleLoadTask;
    private boolean pendingIdleLoadReadOnly;
    /** Префикс на прошлом шаге фильтра (тот же anchor, смена «сооб»→«мета»). */
    private String lastTrackedFilter = ""; //$NON-NLS-1$

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
        ICompletionProposal[] cache = fullListCache;
        if (cache == null || cache.length == 0)
            return ""; //$NON-NLS-1$

        // Собираем уникальные типы родителя, сохраняя порядок первого вхождения
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
        for (ICompletionProposal p : cache)
        {
            String display = displayString(unwrapProposal(p));
            if (display == null)
                continue;
            String parentType = extractParentType(display);
            if (parentType != null && !parentType.isEmpty())
                types.add(parentType);
        }

        if (types.isEmpty())
            return ""; //$NON-NLS-1$

        // Если все элементы одного типа — показываем просто тип
        if (types.size() == 1)
            return types.iterator().next();

        // Несколько типов — счётчик + первый (D основной) тип
        return "(" + types.size() + ") " + types.iterator().next(); //$NON-NLS-1$ //$NON-NLS-2$
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
        cancelIdleFullListLoad();
        pendingEagerLoadTask = null;
        lastTrackedFilter = ""; //$NON-NLS-1$
        ContentAssistDebug.log("invalidateCache"); //$NON-NLS-1$
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
        if (processor != null)
        {
            processor.onFilterPrefixChanged(viewer, caret, previous,
                SmartFilterTracker.getCurrentFilter());
            processor.scheduleEagerFullListLoad(viewer);
            processor.rescheduleIdleFullListLoad(viewer, false);
        }
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

        if (!shouldInvalidateInterimCache(previous, next))
        {
            lastTrackedFilter = next;
            return;
        }
        invalidateInterimListCache(viewer);
        ContentAssistDebug.log("fullList prefix change \"" + previous + "\" -> \"" + next //$NON-NLS-1$ //$NON-NLS-2$
            + "\" reload"); //$NON-NLS-1$
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

    private void invalidateInterimListCache(ITextViewer viewer)
    {
        lastStableEmptyList = EMPTY;
        lastStableDelegateList = EMPTY;
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
            ContentAssistDebug.log("loadFullList eager"); //$NON-NLS-1$
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
            return delegate.computeCompletionProposals(viewer, offset);

        if (RepeatedInvocationDetect.isActive())
        {
            ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
            if (assistant != null)
                ContentAssistPopupSync.captureSelectionBeforeFilterToggle(assistant);
            SmartAssistFilterState.toggle();
            ContentAssistSessionReloader.scheduleFilterToggleUiSync();
        }

        int widgetCaret = resolveWidgetCaret(viewer);
        int caret = resolveInvocationCaret(viewer, offset);
        ContentAssistPopupSync.syncPopupOffsetsToCaret(viewer, caret);
        primeAssistContext(viewer, caret);
        IDocument doc = viewer == null ? null : viewer.getDocument();
        String filter = SmartFilterTracker.getCurrentFilter();

        ensureFullListForContext(viewer, doc, caret);

        boolean reloadUnfiltered = SmartAssistFilterState.consumeUnfilteredReloadPending();
        if (reloadUnfiltered && shouldReloadDelegateOnUnfiltered())
        {
            fullListReady = false;
            fullListComplete = false;
            assignFullListCache(EMPTY);
            memberAccessReloadScheduledSeq = -1;
            rescheduleIdleFullListLoad(viewer, true);
        }
        else if (reloadUnfiltered)
        {
            ContentAssistDebug.log("unfilteredReload reuse cache=" + fullListCache.length //$NON-NLS-1$
                + " key=" + fullListContextKey); //$NON-NLS-1$
        }

        int probeOffset = resolveDelegateProbeOffset(viewer, offset, caret);
        ICompletionProposal[] out = resolveProposalList(viewer, probeOffset, caret, filter,
            SmartAssistFilterState.isSmartFilterEnabled());
        if (!SmartAssistFilterState.isSmartFilterEnabled())
        {
            ContentAssistDebug.log("compute UNFILTERED offset=" + offset + " probe=" + probeOffset //$NON-NLS-1$ //$NON-NLS-2$
                + " caret=" + caret + " filter=\"" + filter + "\" count=" + out.length //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " cacheReady=" + fullListReady + ContentAssistDebug.sampleTypes(out, 2)); //$NON-NLS-1$
        }
        else
        {
            String caretSrc = caret != widgetCaret && widgetCaret >= 0
                ? " invocation=" + caret + " widget=" + widgetCaret //$NON-NLS-1$ //$NON-NLS-2$
                : " caret=" + caret; //$NON-NLS-1$
            ContentAssistDebug.log("compute SMART offset=" + offset + " probe=" + probeOffset //$NON-NLS-1$ //$NON-NLS-2$
                + caretSrc + " filter=\"" + filter + "\" cache=" //$NON-NLS-1$ //$NON-NLS-2$
                + fullListCache.length + " count=" + out.length + " cacheReady=" + fullListReady //$NON-NLS-1$ //$NON-NLS-2$
                + ContentAssistDebug.sampleTypes(out, 3));
        }

        return out;
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
        primeFilterTrackerOnly(viewer, caret);
        IDocument doc = viewer == null ? null : viewer.getDocument();
        String filter = SmartFilterTracker.getCurrentFilter();

        ensureFullListForContext(viewer, doc, caret);

        boolean reloadUnfiltered = SmartAssistFilterState.consumeUnfilteredReloadPending();
        if (reloadUnfiltered && shouldReloadDelegateOnUnfiltered())
        {
            fullListReady = false;
            fullListComplete = false;
            assignFullListCache(EMPTY);
            memberAccessReloadScheduledSeq = -1;
            rescheduleIdleFullListLoad(viewer, true);
        }
        else if (reloadUnfiltered)
        {
            ContentAssistDebug.log("unfilteredReload reuse cache=" + fullListCache.length //$NON-NLS-1$
                + " key=" + fullListContextKey); //$NON-NLS-1$
        }

        int probeOffset = resolveDelegateProbeOffset(viewer, offset, caret);
        return resolveProposalList(viewer, probeOffset, caret, filter,
            SmartAssistFilterState.isSmartFilterEnabled());
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

        if (!smartEnabled)
            return resolveAlphabeticalList(viewer, offset, caret);
        if (filter.isEmpty())
            return resolveDelegateOrderedList(viewer, offset, caret);

        ensureAnchorCacheForFilter(viewer, offset, caret);
        scheduleEagerFullListLoad(viewer);
        if (isCacheValidForCaret(doc, caret))
            return filterAndSort(fullListCache, filter);

        ICompletionProposal[] raw = unwrapProposals(fetchDelegateList(viewer, offset, caret));
        absorbInterimIntoCache(viewer, caret, raw);
        ICompletionProposal[] source = resolveFilterSource(raw);
        return filterSmartWithFallback(viewer, offset, caret, source, filter);
    }

    private ICompletionProposal[] resolveFilterSource(ICompletionProposal[] interim)
    {
        if (fullListComplete)
            return fullListCache;
        if (fullListCache.length >= interim.length)
            return fullListCache;
        return interim;
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
        if (!SmartFilterTracker.getCurrentFilter().isEmpty())
            return;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        if (doc == null || caret < 0)
            return;
        if (computeFullListContextKey(doc, caret) != fullListContextKey)
            return;
        ICompletionProposal[] unwrapped = unwrapProposals(raw);
        if (unwrapped.length > fullListCache.length)
        {
            assignFullListCache(unwrapped);
            fullListReady = true;
        }
    }

    private void assignFullListCache(ICompletionProposal[] cache)
    {
        fullListCache = cache != null ? cache : EMPTY;
        rebuildDelegateOrderMap();
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

    /** Алфавитный список при выключенном smart-фильтре. */
    private ICompletionProposal[] resolveAlphabeticalList(ITextViewer viewer, int offset, int caret)
    {
        if (fullListReady && fullListCache.length > 0)
        {
            ICompletionProposal[] built = buildUnfilteredList(fullListCache);
            if (built.length > 0)
            {
                lastStableEmptyList = built;
                return built;
            }
        }
        if (lastStableEmptyList.length > 0)
            return lastStableEmptyList;

        ICompletionProposal[] raw = unwrapProposals(fetchDelegateList(viewer, offset, caret));
        if (raw.length > 0)
        {
            ICompletionProposal[] built = buildUnfilteredList(raw);
            if (built.length > 0)
                lastStableEmptyList = built;
            return built;
        }
        if (fullListCache.length > 0)
            return buildUnfilteredList(fullListCache);
        return EMPTY;
    }

    /** Порядок delegate при smart-фильтре и пустом префиксе (штатный priority, не алфавит). */
    private ICompletionProposal[] resolveDelegateOrderedList(ITextViewer viewer, int offset,
                                                             int caret)
    {
        scheduleEagerFullListLoad(viewer);
        if (fullListReady && fullListCache.length > 0)
        {
            ICompletionProposal[] built = buildDelegateOrderedList(fullListCache);
            if (built.length > 0)
            {
                lastStableDelegateList = built;
                return built;
            }
        }
        if (lastStableDelegateList.length > 0)
            return lastStableDelegateList;

        ICompletionProposal[] raw = fetchFullDelegateListAtAnchor(viewer, offset, caret);
        if (raw.length > 0)
        {
            ICompletionProposal[] built = buildDelegateOrderedList(raw);
            lastStableDelegateList = built;
            return built;
        }
        if (fullListCache.length > 0)
            return buildDelegateOrderedList(fullListCache);
        return EMPTY;
    }

    private ICompletionProposal[] fetchFullDelegateListAtAnchor(ITextViewer viewer, int offset,
                                                                int caret)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int anchor = caret >= 0 ? computeIdentifierWordStart(doc, caret) : offset;
        if (anchor < 0)
            anchor = caret >= 0 ? caret : offset;
        if (doc != null && caret >= 0
            && computeFullListContextKey(doc, caret) != fullListContextKey)
            return EMPTY;
        ICompletionProposal[] nativeList = delegate.computeCompletionProposals(viewer, anchor);
        ICompletionProposal[] raw = unwrapProposals(nativeList != null ? nativeList : EMPTY);
        if (raw.length > 0)
        {
            assignFullListCache(raw);
            fullListReady = true;
            fullListComplete = true;
        }
        return raw;
    }

    /** Синхронная загрузка полного списка у якоря для сортировки с непустым префиксом. */
    private void ensureAnchorCacheForFilter(ITextViewer viewer, int offset, int caret)
    {
        if (fullListComplete || viewer == null)
            return;
        fetchFullDelegateListAtAnchor(viewer, offset, caret);
    }

    private ICompletionProposal[] filterSmartWithFallback(ITextViewer viewer, int offset, int caret,
                                                        ICompletionProposal[] raw, String filter)
    {
        ICompletionProposal[] result = filterAndSort(raw, filter);
        if (result.length > 0 || filter.isEmpty())
            return result;

        if (fullListCache.length > 0 && raw != fullListCache)
        {
            result = filterAndSort(fullListCache, filter);
            if (result.length > 0)
                return result;
        }

        ICompletionProposal[] retry = unwrapProposals(fetchDelegateList(viewer, offset, caret));
        absorbInterimIntoCache(viewer, caret, retry);
        if (retry.length > 0)
        {
            result = filterAndSort(retry, filter);
            if (result.length > 0)
                return result;
            if (fullListCache.length > 0)
            {
                result = filterAndSort(fullListCache, filter);
                if (result.length > 0)
                    return result;
            }
            return EMPTY;
        }
        if (fullListCache.length > 0)
            return filterAndSort(fullListCache, filter);
        return EMPTY;
    }

    private ICompletionProposal[] fetchDelegateList(ITextViewer viewer, int probeOffset, int caret)
    {
        ICompletionProposal[] nativeList = delegate.computeCompletionProposals(viewer, probeOffset);
        if ((nativeList == null || nativeList.length == 0) && caret >= 0 && caret != probeOffset)
            nativeList = delegate.computeCompletionProposals(viewer, caret);
        if ((nativeList == null || nativeList.length == 0) && caret >= 0 && viewer != null)
        {
            IDocument doc = viewer.getDocument();
            if (doc != null)
            {
                int wordStart = computeIdentifierWordStart(doc, caret);
                if (wordStart >= 0 && wordStart != probeOffset)
                    nativeList = delegate.computeCompletionProposals(viewer, wordStart);
            }
        }
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
            ContentAssistDebug.log("loadFullList idle readOnly=" + readOnly); //$NON-NLS-1$
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
        if (!SmartAssistFilterState.isSmartFilterEnabled())
            return true;
        if (isMemberAccessContextChange(document, event))
            return true;
        String filter = computeActiveFilter(document, offset, event);
        if (filter.isEmpty())
        {
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
        int contextKey = computeFullListContextKey(doc, caret);
        if (contextKey != fullListContextKey)
        {
            fullListContextKey = contextKey;
            fullListReady = false;
            fullListComplete = false;
            assignFullListCache(EMPTY);
            lastStableEmptyList = EMPTY;
            lastStableDelegateList = EMPTY;
            lastTrackedFilter = ""; //$NON-NLS-1$
            memberAccessReloadSeq++;
            memberAccessReloadScheduledSeq = -1;
            cancelIdleFullListLoad();
            ContentAssistDebug.log("fullList context change key=" + contextKey //$NON-NLS-1$
                + " caret=" + caret); //$NON-NLS-1$
            rescheduleIdleFullListLoad(viewer, false);
        }
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
        if (raw == null || raw.length == 0)
        {
            ContentAssistDebug.log("loadFullList EMPTY dot=" + dot + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + (forceDelegateReadOnly ? " readOnly" : "")); //$NON-NLS-1$ //$NON-NLS-2$
            fullListReady = false;
            if (dot >= 0)
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
        if (dot >= 0 && fullListCache.length < MIN_STABLE_MEMBER_CACHE)
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
        ICompletionProposal[] best = EMPTY;
        for (int off : offsets)
        {
            if (off < 0)
                continue;
            ICompletionProposal[] raw = delegate.computeCompletionProposals(viewer, off);
            int count = raw != null ? raw.length : 0;
            if (count == 0)
                continue;
            ContentAssistDebug.log("loadFullList probe off=" + off //$NON-NLS-1$
                + " count=" + count + " dot=" + dot); //$NON-NLS-1$ //$NON-NLS-2$
            if (dot < 0 && SmartAssistFilterState.isSmartFilterEnabled())
                return raw;
            if (count > best.length)
                best = raw;
            if (dot >= 0 && best.length >= MIN_STABLE_MEMBER_CACHE)
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
            if (doc != null)
            {
                String prefix = computeIdentifierFilter(doc, caret);
                if (!prefix.isEmpty())
                {
                    int wordStart = caret - prefix.length();
                    if (wordStart >= 0)
                        set.add(wordStart);
                }
            }
            set.add(caret);
            if (assistant != null)
            {
                int popupOffset = ContentAssistPopupSync.getFilterOffset(assistant);
                if (popupOffset >= 0)
                    set.add(popupOffset);
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

    /** Повторная догрузка member-access только при маленьком кэше, без readOnly на UI. */
    private void scheduleMemberAccessReload(ITextViewer viewer, int dotContextKey)
    {
        if (viewer == null)
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
                raw = requestDelegateProposals(viewer, doc, caret, dotContextKey, assistant,
                    offsets);
            else
                raw = probeDelegateAtOffsets(viewer, offsets, dotContextKey);
            if (raw == null || raw.length <= prev)
                return;
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

    /**
     * Старт сессии / sync popup: максимум из виджета, completion и invocation.
     * {@code fInvocationOffset} часто = начало слова (пустой префикс), виджет — каретка.
     */
    static int resolveSessionCaret(ContentAssistant assistant, ITextViewer viewer)
    {
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int best = -1;
        int widget = resolveWidgetCaret(viewer);
        if (widget >= 0)
            best = widget;
        if (assistant != null)
        {
            int completion = ContentAssistPopupSync.getLastCompletionOffset(assistant);
            if (completion >= 0)
                best = Math.max(best, completion);
            int inv = ContentAssistPopupSync.getInvocationOffset(assistant);
            if (inv >= 0)
                best = Math.max(best, inv);
        }
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

    /**
     * Полный список при выключенном smart-фильтре: все элементы, алфавит;
     * {@link SmartCompletionProposal#validate} пропускает всё, подсветка по {@link SmartFilterTracker}.
     */
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
            return EMPTY;

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
        return result;
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
        if (p instanceof ConfigurableCompletionProposal)
            return ((ConfigurableCompletionProposal) p).getPriority();
        return Integer.MIN_VALUE;
    }

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
                while (pos > 0 && isFilterChar(doc.getChar(pos - 1)))
                    pos--;
                if (pos <= 0 || doc.getChar(pos - 1) != '.')
                    return -1;
                return pos - 1;
            }
            catch (Exception ignored)
            {
                return -1;
            }
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
