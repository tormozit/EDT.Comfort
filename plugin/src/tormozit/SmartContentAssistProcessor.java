package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.ISourceViewer;
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
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.mcore.TypeItem;

/**
 * Обёртка над {@link IContentAssistProcessor}.
 *
 * <p><b>Полный список</b> (п.1): при пустом фильтре — кэш delegate без smart-фильтрации.
 * <p><b>Фильтр+сорт+цвет</b> (п.2): при непустом фильтре — {@link #filterAndSort} по кэшу.
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
    private boolean fullListReady = false;
    /** Позиция '.' контекста member-access для кэша, {@code Integer.MIN_VALUE} — без точки. */
    private int fullListContextKey = Integer.MIN_VALUE;
    /** Отмена устаревших {@link #scheduleMemberAccessReload}. */
    private int memberAccessReloadSeq = 0;
    /** Уже запланирована догрузка для текущего {@link #memberAccessReloadSeq}. */
    private int memberAccessReloadScheduledSeq = -1;

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
        fullListCache = EMPTY;
        fullListContextKey = Integer.MIN_VALUE;
        memberAccessReloadSeq++;
        memberAccessReloadScheduledSeq = -1;
        ContentAssistDebug.log("invalidateCache"); //$NON-NLS-1$
    }

    static void clearLastComputeCaret()
    {
        LAST_COMPUTE_CARET.remove();
    }

    /** Каретка и префикс фильтра для validate до первого {@link #computeCompletionProposals}. */
    static void primeAssistContext(ITextViewer viewer, int caret)
    {
        if (caret < 0)
            return;
        LAST_COMPUTE_CARET.set(caret);
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        SmartFilterTracker.setCurrentFilter(computeIdentifierFilter(doc, caret));
    }

    /** Заполняет кэш полного списка (вызов при старте сессии assist). */
    public void warmFullListCache(ITextViewer viewer)
    {
        ContentAssistDebug.log("warmFullListCache"); //$NON-NLS-1$
        int caret = resolveCaretOffset(viewer, 0);
        primeAssistContext(viewer, caret);
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        ensureFullListForContext(viewer, doc, caret);
        loadFullList(viewer);
    }

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset)
    {
        if (RepeatedInvocationDetect.isActive())
        {
            ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
            if (assistant != null)
                ContentAssistPopupSync.captureSelectionBeforeFilterToggle(assistant);
            SmartAssistFilterState.toggle();
            ContentAssistSessionReloader.scheduleFilterToggleUiSync();
        }

        int caret = resolveCaretOffset(viewer, offset);
        primeAssistContext(viewer, caret);
        if (caret != offset)
            ContentAssistPopupSync.syncPopupOffsetsToCaret(viewer, caret);
        IDocument doc = viewer == null ? null : viewer.getDocument();
        String filter = SmartFilterTracker.getCurrentFilter();

        ensureFullListForContext(viewer, doc, caret);

        boolean reloadUnfiltered = SmartAssistFilterState.consumeUnfilteredReloadPending();
        if (reloadUnfiltered)
        {
            if (shouldReloadDelegateOnUnfiltered())
            {
                fullListReady = false;
                fullListCache = EMPTY;
                memberAccessReloadScheduledSeq = -1;
                loadFullList(viewer, true);
            }
            else
            {
                ContentAssistDebug.log("unfilteredReload reuse cache=" + fullListCache.length //$NON-NLS-1$
                    + " key=" + fullListContextKey); //$NON-NLS-1$
            }
        }
        else if (!fullListReady)
        {
            loadFullList(viewer, false);
        }

        ICompletionProposal[] out;
        if (!SmartAssistFilterState.isSmartFilterEnabled())
        {
            out = buildUnfilteredList(fullListCache, filter);
            ContentAssistDebug.log("compute UNFILTERED offset=" + offset + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + " filter=\"" + filter + "\" count=" + out.length //$NON-NLS-1$ //$NON-NLS-2$
                + ContentAssistDebug.sampleTypes(out, 2));
        }
        else if (filter.isEmpty())
        {
            out = buildUnfilteredList(fullListCache, "");
            ContentAssistDebug.log("compute FULL offset=" + offset + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + " count=" + out.length //$NON-NLS-1$
                + ContentAssistDebug.sampleTypes(out, 2));
        }
        else
        {
            out = filterAndSort(fullListCache, filter);
            ContentAssistDebug.log("compute FILTER offset=" + offset + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + " filter=\"" + filter + "\" cache=" + fullListCache.length //$NON-NLS-1$ //$NON-NLS-2$
                + " out=" + out.length + ContentAssistDebug.sampleTypes(out, 3)); //$NON-NLS-1$
        }

        return out;
    }

    // ---- Точка вызова алгоритма для popup.validate / isValidFor ----------------

    static boolean proposalMatchesFilter(ICompletionProposal proposal, IDocument document,
                                         int offset, DocumentEvent event)
    {
        if (!SmartAssistFilterState.isSmartFilterEnabled())
            return true;
        if (isMemberAccessContextChange(document, event))
            return true;
        String filter = computeActiveFilter(document, offset, event);
        if (filter.isEmpty())
            return true;
        boolean ok = computeScore(new SmartCodeMatcher(filter), proposal) > 0;
        ContentAssistDebug.logValidate(ok, filter, proposal, offset);
        return ok;
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
            fullListCache = EMPTY;
            memberAccessReloadSeq++;
            memberAccessReloadScheduledSeq = -1;
            ContentAssistDebug.log("fullList context change key=" + contextKey //$NON-NLS-1$
                + " caret=" + caret); //$NON-NLS-1$
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
        int caret = resolveCaretOffset(viewer, 0);
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        int dot = ReceiverTypeLabel.findMemberAccessDot(doc, caret);

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
        fullListCache = unwrapProposals(raw);
        fullListReady = true;
        ContentAssistDebug.log("loadFullList count=" + fullListCache.length //$NON-NLS-1$
            + " dot=" + dot + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
            + (forceDelegateReadOnly ? " readOnly" : "")); //$NON-NLS-1$ //$NON-NLS-2$
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
            set.add(caret);
            if (!SmartAssistFilterState.isSmartFilterEnabled() && doc != null)
            {
                String prefix = computeIdentifierFilter(doc, caret);
                if (!prefix.isEmpty())
                {
                    int wordStart = caret - prefix.length();
                    if (wordStart >= 0)
                        set.add(wordStart);
                }
            }
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
            int caret = resolveCaretOffset(viewer, 0);
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
            fullListCache = unwrapProposals(raw);
            fullListReady = true;
            if (fullListCache.length >= MIN_STABLE_MEMBER_CACHE)
                memberAccessReloadScheduledSeq = memberAccessReloadSeq;
            ContentAssistSessionReloader.refreshPopupIfOpen();
        }
        catch (Exception ignored) {}
    }

    private static int resolveCaretOffset(ITextViewer viewer, int fallback)
    {
        if (viewer != null)
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
        }
        return fallback;
    }

    /**
     * Полный список без smart-фильтрации: все элементы кэша, алфавит, подсветка по префиксу
     * в редакторе ({@code highlightFilter}), validate не сужает список.
     */
    private ICompletionProposal[] buildUnfilteredList(ICompletionProposal[] raw, String highlightFilter)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;

        List<ICompletionProposal> list = new ArrayList<>(raw.length);
        for (ICompletionProposal p : raw)
            list.add(unwrapProposal(p));
        list.sort((a, b) -> compareDisplayStrings(a, b));

        SmartCodeMatcher matcher = new SmartCodeMatcher(highlightFilter != null ? highlightFilter : "");
        ICompletionProposal[] result = new ICompletionProposal[list.size()];
        for (int i = 0; i < list.size(); i++)
            result[i] = new SmartCompletionProposal(list.get(i), matcher);
        return result;
    }

    // ---- Фильтрация + сортировка + обёртка ------------------------------------

    private ICompletionProposal[] filterAndSort(ICompletionProposal[] raw, String filter)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;

        SmartCodeMatcher matcher = new SmartCodeMatcher(filter);
        List<ICompletionProposal> filtered = new ArrayList<>(raw.length);
        int[] scores = new int[raw.length];

        for (ICompletionProposal p : raw)
        {
            int score = computeScore(matcher, p);
            if (score > 0)
            {
                scores[filtered.size()] = score;
                filtered.add(p);
            }
        }

        if (filtered.isEmpty())
            return EMPTY;

        Integer[] idx = new Integer[filtered.size()];
        for (int i = 0; i < idx.length; i++)
            idx[i] = i;

        final int[] s = Arrays.copyOf(scores, filtered.size());
        Arrays.sort(idx, (a, b) -> {
            if (s[a] != s[b])
                return Integer.compare(s[b], s[a]);
            return compareDisplayStrings(filtered.get(a), filtered.get(b));
        });

        ICompletionProposal[] result = new ICompletionProposal[idx.length];
        for (int i = 0; i < idx.length; i++)
            result[i] = new SmartCompletionProposal(filtered.get(idx[i]), matcher);
        return result;
    }

    static int computeScore(SmartCodeMatcher matcher, ICompletionProposal proposal)
    {
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
        int s1 = computeScore(matcher, p1);
        int s2 = computeScore(matcher, p2);
        if (s1 != s2)
            return Integer.compare(s2, s1);
        return compareDisplayStrings(p1, p2);
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
        int caret = offset;
        if (event != null)
        {
            try
            {
                String text = event.getText();
                int eventEnd = event.getOffset() + (text != null ? text.length() : 0);
                caret = Math.max(caret, eventEnd);
            }
            catch (Exception ignored) {}
        }
        else
        {
            Integer last = LAST_COMPUTE_CARET.get();
            if (last != null && last >= 0)
                caret = last;
            else
            {
                org.eclipse.jface.text.source.ISourceViewer active =
                    ContentAssistSessionReloader.getActiveViewer();
                if (active != null)
                {
                    int widgetCaret = resolveCaretOffset(active, offset);
                    if (widgetCaret >= 0)
                        caret = widgetCaret;
                }
                else if (doc != null)
                    caret = doc.getLength();
            }
        }
        if (doc != null && caret > doc.getLength())
            caret = doc.getLength();
        return caret;
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
