package tormozit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobFunction;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

/**
 * Фоновый расчёт колонок контекста BSL-вхождения — общий для табличных списков вхождений плагина:
 * {@link RefactoringPreviewTableHook} (страница «Вносимые изменения» мастера рефакторинга) и
 * {@link BslReferenceSearchTableHook} (панель результатов «Найти ссылки» на программный элемент).
 *
 * <p>Два прохода, чтобы тяжесть не блокировала быстрые колонки:
 * <ol>
 * <li><b>Быстрый</b> ({@code fastJob}, системный): номер и текст строки с диапазоном подсветки
 * вхождения, «Родитель», «Категория», «Метод» — лексический разбор текста модуля
 * ({@link BslOccurrenceContextResolver}, {@link BslModuleMethodResolver}); позиция вхождения — из
 * модели ({@code resolveRegion}), кэшируется потребителем для второго прохода.</li>
 * <li><b>Тяжёлый</b> ({@code typeJob}): «Тип родителя» — поднимает и связывает модель BSL на каждый
 * модуль. Идёт последним. Строки в поле зрения считаются всегда автоматически; если всего строк
 * больше {@code typeAutoThreshold}, остальные ждут явной команды {@link #computeAllTypes()}
 * («Рассчитать типы»). Прогресс — счётчик в заголовке колонки; при большом объёме проход
 * НЕсистемный и виден в панели «Ход выполнения» с кнопкой остановки.</li>
 * </ol>
 *
 * <p>Оба прохода считают видимые строки первыми; приоритет пересматривается на прокрутку
 * ({@link #trackViewportScrolling()}). Устаревший проход отбрасывается по поколению, при перестроении
 * списка / закрытии окна — {@link #cancel()}.
 */
final class OccurrenceContextResolveJob
{
    /** Строка списка вхождений, которую движок дозаполняет в фоне. */
    interface Target
    {
        /** Файл модуля вхождения; {@code null} — колонки контекста этой строке не нужны. */
        IFile file();

        /** {@code true} — быстрый проход по этой строке ещё не выполнен. */
        boolean needsContext();

        /**
         * Модельные координаты вхождения {@code [offset, length]} в тексте модуля, либо {@code null},
         * если позицию восстановить не удалось. Вызывается в фоновом потоке.
         */
        int[] resolveRegion();

        /**
         * Координаты вхождения, вычисленные быстрым проходом; {@code null} — проход ещё не прошёл или
         * позиция не восстановлена. Тяжёлый проход берёт их отсюда, чтобы не считать заново.
         */
        int[] resolvedRegion();

        /** Применить результат быстрого прохода. Фоновый поток. */
        void applyFast(FastContext context);

        /** Применить «Тип родителя» ({@code ""} — вычислить не удалось). Фоновый поток. */
        void applyParentType(String parentType);

        /** Текст строки кода вхождения (из {@link FastContext#lineText}) — для колонки «Текст». */
        String occurrenceLineText();

        /** Смещение вхождения внутри {@link #occurrenceLineText()} — для подсветки. */
        int occurrenceHighlightStart();

        int occurrenceHighlightLength();
    }

    /**
     * Колонка «Текст»: строка кода из модуля с подсветкой найденного вхождения
     * ({@link SmartMatchHighlight#styler()} по диапазону из {@link FastContext}). Провайдер —
     * {@link SelectionAwareStyledCellLabelProvider} (цвета {@code StyleRange} на выделенной строке).
     * Ширину/раскладку и {@code interaction.setOwnerDrawColumns} ставит потребитель.
     */
    static final String TEXT_COLUMN_TITLE = "Текст"; //$NON-NLS-1$

    static TableViewerColumn addTextColumn(TableViewer viewer)
    {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(TEXT_COLUMN_TITLE);
        column.setLabelProvider(new SelectionAwareStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (!(element instanceof Target row))
                    return new StyledString(""); //$NON-NLS-1$
                String text = row.occurrenceLineText();
                if (text == null || text.isEmpty())
                    return new StyledString(""); //$NON-NLS-1$
                StyledString styled = new StyledString(text);
                int start = row.occurrenceHighlightStart();
                int length = row.occurrenceHighlightLength();
                if (length > 0 && start >= 0 && start + length <= text.length())
                    styled.setStyle(start, length, SmartMatchHighlight.styler());
                return styled;
            }

            @Override public Image getImage(Object element) { return null; }
            @Override public void addListener(ILabelProviderListener listener) {}
            @Override public void dispose() {}
            @Override public boolean isLabelProperty(Object element, String property) { return false; }
            @Override public void removeListener(ILabelProviderListener listener) {}
        }));
        return column;
    }

    /** Результат быстрого прохода по одному вхождению. */
    static final class FastContext
    {
        final int offset;
        final int length;
        /** Номер строки (1-based); {@code 0} — позиция не восстановлена. */
        final int line;
        /** Текст строки модуля без ведущих/хвостовых пробелов. */
        final String lineText;
        /** Смещение и длина вхождения внутри {@link #lineText} — для подсветки в колонке «Текст». */
        final int highlightStart;
        final int highlightLength;
        final String parent;
        final String syntaxKind;
        final String method;
        /** Нужен ли этой строке тяжёлый проход «Тип родителя» (не комментарий/литерал). */
        final boolean wantsParentType;

        private FastContext(int offset, int length, int line, String lineText, int highlightStart,
            int highlightLength, String parent, String syntaxKind, String method, boolean wantsParentType)
        {
            this.offset = offset;
            this.length = length;
            this.line = line;
            this.lineText = lineText;
            this.highlightStart = highlightStart;
            this.highlightLength = highlightLength;
            this.parent = parent;
            this.syntaxKind = syntaxKind;
            this.method = method;
            this.wantsParentType = wantsParentType;
        }

        static FastContext empty()
        {
            return new FastContext(-1, 0, 0, "", 0, 0, "", "", "", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }

    /** Тема временного лога — снять после подтверждения производительности. */
    private static final String LOG = "occ-context"; //$NON-NLS-1$
    private static final int FAST_BATCH = 150;
    /** Тяжёлый проход: строка = секунды, поэтому публикуем каждую (плавный % и подсветка ячейки). */
    private static final int TYPE_BATCH = 1;
    /**
     * Непустой массив свойств для {@code viewer.update}: у таблицы с {@code ViewerComparator}
     * (или фильтрами) вызов с {@code null} JFace трактует как «изменилось всё» → ПОЛНЫЙ
     * {@code internalRefresh} на каждый апдейт (на 2000 строк это фриз, ячейки не обновляются до
     * конца прохода). С непустым массивом и {@code isSorterProperty()==false} — только перерисовка
     * ячеек.
     */
    private static final String[] CELL_UPDATE = {"tormozit.occurrenceContext"}; //$NON-NLS-1$

    private final Table table;
    private final TableViewer viewer;
    private final TableColumn parentTypeColumn;
    private final String parentTypeTitle;
    /** Больше этого числа строк, требующих «Тип родителя», — автопроход только по видимой области. */
    private final int typeAutoThreshold;
    /** Вызывается в UI-потоке при изменении числа отложенных строк «Тип родителя» — обновить кнопку. */
    private final Runnable onTypePendingChanged;

    private final Object queueLock = new Object();
    private Deque<Target> fastQueue;
    private Deque<Target> typeQueue;
    /** Строки «Тип родителя», ждущие явной команды {@link #computeAllTypes()} (режим &gt; порога). */
    private final List<Target> typeDeferred = new ArrayList<>();
    private boolean typeDeferralActive;

    private Job fastJob;
    private Job typeJob;
    private volatile boolean typeJobActive;
    /** Пользователь остановил тяжёлый проход — сам не перезапускается, только по кнопке. */
    private volatile boolean typeStopped;
    private volatile long generation;
    /** Всего строк поставлено в тяжёлый проход (растёт по мере прокрутки / нажатия кнопки). */
    private volatile int typeEnqueuedTotal;
    private final AtomicInteger typeDone = new AtomicInteger();
    private boolean reprioritizeScheduled;
    private boolean scrollTracked;

    OccurrenceContextResolveJob(Table table, TableViewer viewer, TableColumn parentTypeColumn,
        String parentTypeTitle, int typeAutoThreshold, Runnable onTypePendingChanged)
    {
        this.table = table;
        this.viewer = viewer;
        this.parentTypeColumn = parentTypeColumn;
        this.parentTypeTitle = parentTypeTitle;
        this.typeAutoThreshold = typeAutoThreshold;
        this.onTypePendingChanged = onTypePendingChanged;
    }

    /** Сколько строк «Тип родителя» отложено (ждут кнопки «Рассчитать типы»); 0 — режим автопрохода. */
    int deferredTypeCount()
    {
        synchronized (queueLock)
        {
            return typeDeferred.size();
        }
    }

    /** Кнопка «Рассчитать типы»: досчитать «Тип родителя» для всех отложенных строк. UI-поток. */
    void computeAllTypes()
    {
        long gen = generation;
        synchronized (queueLock)
        {
            if (typeDeferred.isEmpty())
                return;
            enqueueType(typeDeferred);
            typeDeferred.clear();
            typeDeferralActive = false;
        }
        typeStopped = false;
        updateProgress();
        fireTypePendingChanged();
        maybeStartTypeJob(gen);
    }

    /**
     * Подписаться на прокрутку таблицы: видимые строки уходят в начало очередей, для видимой области
     * тяжёлый проход запускается даже в отложенном режиме. Вызывать один раз (UI-поток).
     */
    void trackViewportScrolling()
    {
        if (scrollTracked || table.isDisposed())
            return;
        scrollTracked = true;
        ScrollBar vBar = table.getVerticalBar();
        if (vBar != null)
            vBar.addListener(SWT.Selection, event -> scheduleReprioritize());
        table.addListener(SWT.MouseWheel, event -> scheduleReprioritize());
        table.addListener(SWT.KeyDown, event -> scheduleReprioritize());
        table.addListener(SWT.Resize, event -> scheduleReprioritize());
    }

    /** Перезапустить расчёт под текущий набор строк. Вызывать в UI-потоке. */
    void reschedule(List<? extends Target> targets)
    {
        cancel();
        List<Target> pending = new ArrayList<>();
        for (Target target : targets)
        {
            if (target.needsContext() && target.file() != null)
                pending.add(target);
        }
        if (pending.isEmpty())
        {
            updateProgress();
            fireTypePendingChanged();
            Global.tempLog(LOG, "reschedule: нечего считать (" + targets.size() + " строк на входе)"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        long gen = ++generation;
        List<Target> ordered = groupByFile(visibleFirst(pending));
        synchronized (queueLock)
        {
            fastQueue = new ArrayDeque<>(ordered);
        }
        Global.tempLog(LOG, "reschedule: " + ordered.size() + " строк, быстрый проход (gen=" + gen + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Job created = Job.create("Комфорт: контекст вхождений BSL", //$NON-NLS-1$
            (IJobFunction)(monitor -> runFast(ordered.size(), gen, monitor)));
        created.setSystem(true);
        fastJob = created;
        created.schedule();
    }

    /** Отменить оба прохода и обесценить отложенные результаты. Вызывать в UI-потоке. */
    void cancel()
    {
        generation++;
        synchronized (queueLock)
        {
            fastQueue = null;
            typeQueue = null;
            typeDeferred.clear();
            typeDeferralActive = false;
        }
        cancelJob(fastJob);
        cancelJob(typeJob);
        fastJob = null;
        typeJob = null;
        typeJobActive = false;
        typeStopped = false;
        typeEnqueuedTotal = 0;
        typeDone.set(0);
        fireTypePendingChanged();
    }

    private static void cancelJob(Job job)
    {
        if (job != null)
            job.cancel();
    }

    // ---- Приоритет видимых строк ----

    private void scheduleReprioritize()
    {
        if (reprioritizeScheduled || table.isDisposed())
            return;
        reprioritizeScheduled = true;
        table.getDisplay().timerExec(120, () ->
        {
            reprioritizeScheduled = false;
            reprioritizeVisible();
        });
    }

    private void reprioritizeVisible()
    {
        if (table.isDisposed())
            return;
        List<Target> visible = visibleTargets();
        if (visible.isEmpty())
            return;
        long gen = generation;
        boolean pulled = false;
        synchronized (queueLock)
        {
            moveToFront(fastQueue, visible);
            moveToFront(typeQueue, visible);
            // Видимые строки «Тип родителя» досчитываем автоматически (докрутил — посчиталось), но
            // НЕ после явной остановки пользователем: тогда только по кнопке.
            if (typeDeferralActive && !typeStopped && !typeDeferred.isEmpty())
            {
                Set<Target> vis = new HashSet<>(visible);
                List<Target> now = new ArrayList<>();
                for (Target target : typeDeferred)
                {
                    if (vis.contains(target))
                        now.add(target);
                }
                if (!now.isEmpty())
                {
                    typeDeferred.removeAll(now);
                    enqueueType(groupByFile(now));
                    pulled = true;
                }
            }
        }
        if (pulled)
        {
            updateProgress();
            fireTypePendingChanged();
            maybeStartTypeJob(gen);
        }
    }

    private static void moveToFront(Deque<Target> queue, List<Target> visible)
    {
        if (queue == null || queue.size() <= 1)
            return;
        Set<Target> inQueue = new HashSet<>(queue);
        LinkedHashSet<Target> front = new LinkedHashSet<>();
        for (Target target : visible)
        {
            if (inQueue.contains(target))
                front.add(target);
        }
        if (front.isEmpty() || queue.size() <= front.size())
            return;
        queue.removeAll(front);
        Deque<Target> reordered = new ArrayDeque<>(front);
        reordered.addAll(queue);
        queue.clear();
        queue.addAll(reordered);
    }

    // ---- Быстрый проход ----

    private IStatus runFast(int total, long gen, IProgressMonitor monitor)
    {
        long startedAt = System.currentTimeMillis();
        Global.tempLog(LOG, "fast start: total=" + total + " gen=" + gen); //$NON-NLS-1$ //$NON-NLS-2$
        Map<IFile, String> texts = new IdentityHashMap<>();
        List<Target> batch = new ArrayList<>();
        List<Target> forType = new ArrayList<>();
        int done = 0;
        while (true)
        {
            if (monitor.isCanceled() || gen != generation)
            {
                Global.tempLog(LOG, "fast cancelled at " + done + "/" + total); //$NON-NLS-1$ //$NON-NLS-2$
                return Status.CANCEL_STATUS;
            }
            Target target;
            synchronized (queueLock)
            {
                target = fastQueue != null ? fastQueue.pollFirst() : null;
            }
            if (target == null)
                break;
            IFile file = target.file();
            if (!texts.containsKey(file))
                texts.put(file, BslModuleMethodResolver.moduleText(file));
            long t0 = System.currentTimeMillis();
            FastContext fc = resolveFast(target, texts.get(file));
            long spent = System.currentTimeMillis() - t0;
            if (spent > 150)
                Global.tempLog(LOG, "slow fast row " + spent + " ms: " //$NON-NLS-1$ //$NON-NLS-2$
                    + (file != null ? file.getName() : "?")); //$NON-NLS-1$
            if (fc.wantsParentType)
                forType.add(target);
            else
                target.applyParentType(""); // комментарий/литерал/неразбор: типа родителя нет //$NON-NLS-1$
            batch.add(target);
            done++;
            if (batch.size() >= FAST_BATCH || fastQueueEmpty())
            {
                publishFast(new ArrayList<>(batch), gen);
                batch.clear();
            }
        }
        Global.tempLog(LOG, "fast done: " + done + "/" + total + " за " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (System.currentTimeMillis() - startedAt) + " ms; тип нужен " + forType.size()); //$NON-NLS-1$
        if (gen == generation && !monitor.isCanceled())
            startTypePass(forType, gen);
        return Status.OK_STATUS;
    }

    private static FastContext resolveFast(Target target, String content)
    {
        int[] region = target.resolveRegion();
        if (region == null || content == null)
        {
            target.applyFast(FastContext.empty());
            return FastContext.empty();
        }
        int offset = region[0];
        int length = region[1];
        int lineStart = lineStart(content, offset);
        int lineEnd = lineEnd(content, offset);
        String raw = content.substring(lineStart, lineEnd);
        int lead = raw.length() - raw.stripLeading().length();
        String lineText = raw.strip();
        int hlStart = Math.max(0, offset - lineStart - lead);
        int hlLen = Math.max(0, Math.min(length, lineText.length() - hlStart));
        int line = lineNumber(content, offset);
        String parent = BslOccurrenceContextResolver.parentText(content, offset);
        String syntaxKind = BslOccurrenceContextResolver.syntaxKind(content, offset, length);
        String method = BslModuleMethodResolver.methodAtLine(target.file(), line);
        boolean wantsType = !BslOccurrenceContextResolver.KIND_COMMENT.equals(syntaxKind)
            && !BslOccurrenceContextResolver.KIND_LITERAL.equals(syntaxKind);
        FastContext fc = new FastContext(offset, length, line, lineText, hlStart, hlLen, parent,
            syntaxKind, method != null ? method : "", wantsType); //$NON-NLS-1$
        target.applyFast(fc);
        return fc;
    }

    private void publishFast(List<Target> batch, long gen)
    {
        runOnUi(() ->
        {
            if (gen != generation)
                return;
            viewer.update(batch.toArray(), CELL_UPDATE);
        });
    }

    // ---- Тяжёлый проход «Тип родителя» ----

    private void startTypePass(List<Target> forType, long gen)
    {
        Global.tempLog(LOG, "type pass: кандидатов " + forType.size() + ", порог " + typeAutoThreshold); //$NON-NLS-1$ //$NON-NLS-2$
        runOnUi(() ->
        {
            if (gen != generation || table.isDisposed())
                return;
            Set<Target> visible = new HashSet<>(visibleTargets());
            boolean start;
            synchronized (queueLock)
            {
                typeEnqueuedTotal = 0;
                typeDone.set(0);
                typeDeferred.clear();
                typeStopped = false;
                if (forType.size() <= typeAutoThreshold)
                {
                    // Немного — считаем всё автоматически.
                    typeDeferralActive = false;
                    enqueueType(groupByFile(sortVisibleFirst(forType, visible)));
                }
                else
                {
                    // Много — автоматически только видимую область, остальное по кнопке «Рассчитать типы».
                    typeDeferralActive = true;
                    List<Target> now = new ArrayList<>();
                    for (Target target : forType)
                    {
                        if (visible.contains(target))
                            now.add(target);
                        else
                            typeDeferred.add(target);
                    }
                    enqueueType(groupByFile(now));
                }
                start = typeQueue != null && !typeQueue.isEmpty();
            }
            updateProgress();
            fireTypePendingChanged();
            if (start)
                maybeStartTypeJob(gen);
        });
    }

    /** Только под {@link #queueLock}. */
    private void enqueueType(Collection<Target> targets)
    {
        if (targets.isEmpty())
            return;
        if (typeQueue == null)
            typeQueue = new ArrayDeque<>();
        typeQueue.addAll(targets);
        typeEnqueuedTotal += targets.size();
    }

    private void maybeStartTypeJob(long gen)
    {
        if (typeJobActive || typeStopped || gen != generation)
            return;
        boolean empty;
        boolean visibleJob;
        synchronized (queueLock)
        {
            empty = typeQueue == null || typeQueue.isEmpty();
            // НЕсистемный (виден в «Ходе выполнения» с кнопкой остановки) — когда проход
            // запущен кнопкой или объём больше порога. Иначе системный, без шума.
            visibleJob = typeDeferralActive || typeEnqueuedTotal > typeAutoThreshold;
        }
        if (empty)
            return;
        typeJobActive = true;
        Job created = new Job("Комфорт: определение типа родителя вхождений") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                return runType(gen, monitor);
            }
        };
        created.setSystem(!visibleJob);
        created.setPriority(Job.DECORATE);
        typeJob = created;
        created.schedule();
    }

    private IStatus runType(long gen, IProgressMonitor monitor)
    {
        long startedAt = System.currentTimeMillis();
        int total;
        synchronized (queueLock)
        {
            total = typeQueue != null ? typeQueue.size() : 0;
        }
        Map<IFile, String> texts = new IdentityHashMap<>();
        List<Target> batch = new ArrayList<>();
        int processed = 0;
        boolean userStopped = false;
        try
        {
            monitor.beginTask("Тип родителя вхождений", Math.max(total, 1)); //$NON-NLS-1$
            while (true)
            {
                if (monitor.isCanceled())
                {
                    userStopped = true;
                    Global.tempLog(LOG, "type STOPPED by user at " + typeDone.get() + "/" + total); //$NON-NLS-1$ //$NON-NLS-2$
                    return Status.CANCEL_STATUS;
                }
                if (gen != generation)
                    return Status.CANCEL_STATUS;
                Target target;
                synchronized (queueLock)
                {
                    target = typeQueue != null ? typeQueue.pollFirst() : null;
                }
                if (target == null)
                    break;
                IFile file = target.file();
                if (!texts.containsKey(file))
                    texts.put(file, BslModuleMethodResolver.moduleText(file));
                int[] region = target.resolvedRegion();
                long t0 = System.currentTimeMillis();
                String type = region != null && texts.get(file) != null
                    ? BslOccurrenceContextResolver.parentType(file, texts.get(file), region[0])
                    : ""; //$NON-NLS-1$
                long spent = System.currentTimeMillis() - t0;
                if (spent > 400)
                    Global.tempLog(LOG, "slow type " + spent + " ms: " //$NON-NLS-1$ //$NON-NLS-2$
                        + (file != null ? file.getName() : "?")); //$NON-NLS-1$
                target.applyParentType(type);
                batch.add(target);
                typeDone.incrementAndGet();
                monitor.worked(1);
                processed++;
                if (batch.size() >= TYPE_BATCH || typeQueueEmpty())
                {
                    publishType(new ArrayList<>(batch), gen);
                    batch.clear();
                }
            }
            Global.tempLog(LOG, "type done: " + processed + "/" + total + " за " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (System.currentTimeMillis() - startedAt) + " ms"); //$NON-NLS-1$
            return Status.OK_STATUS;
        }
        finally
        {
            monitor.done();
            typeJobActive = false;
            if (!batch.isEmpty())
                publishType(new ArrayList<>(batch), gen);
            if (userStopped)
            {
                // Остановлено пользователем — недосчитанное в «отложенные», сам проход НЕ оживает.
                typeStopped = true;
                synchronized (queueLock)
                {
                    if (typeQueue != null)
                    {
                        typeDeferred.addAll(typeQueue);
                        typeQueue.clear();
                    }
                    typeDeferralActive = true;
                    typeEnqueuedTotal = typeDone.get();
                }
                runOnUi(this::updateProgress);
                fireTypePendingChanged();
            }
            else if (gen == generation && !typeQueueEmpty())
            {
                // В очередь докинули строки, пока шёл этот проход (нажали кнопку / доскроллили) —
                // maybeStartTypeJob тогда молчал из-за typeJobActive. Продолжаем.
                runOnUi(() -> maybeStartTypeJob(gen));
            }
        }
    }

    private void publishType(List<Target> batch, long gen)
    {
        runOnUi(() ->
        {
            if (gen != generation || table.isDisposed())
                return;
            viewer.update(batch.toArray(), CELL_UPDATE);
            updateProgress();
        });
    }

    private void fireTypePendingChanged()
    {
        if (onTypePendingChanged != null)
            runOnUi(onTypePendingChanged);
    }

    private void updateProgress()
    {
        if (parentTypeColumn == null || parentTypeColumn.isDisposed())
            return;
        int total;
        int deferred;
        synchronized (queueLock)
        {
            total = typeEnqueuedTotal;
            deferred = typeDeferred.size();
        }
        int done = typeDone.get();
        String suffix;
        if (total > 0 && done < total)
            suffix = " (" + done + "/" + total + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        else if (deferred > 0)
            suffix = " (+" + deferred + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        else
            suffix = ""; //$NON-NLS-1$
        parentTypeColumn.setText(parentTypeTitle + suffix);
    }

    // ---- Вспомогательное ----

    private void runOnUi(Runnable runnable)
    {
        if (table.isDisposed())
            return;
        Display display = table.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (!table.isDisposed())
                runnable.run();
        });
    }

    private boolean fastQueueEmpty()
    {
        synchronized (queueLock)
        {
            return fastQueue == null || fastQueue.isEmpty();
        }
    }

    private boolean typeQueueEmpty()
    {
        synchronized (queueLock)
        {
            return typeQueue == null || typeQueue.isEmpty();
        }
    }

    private List<Target> visibleTargets()
    {
        List<Target> result = new ArrayList<>();
        if (table.isDisposed())
            return result;
        int top = table.getTopIndex();
        int itemHeight = Math.max(table.getItemHeight(), 1);
        int visible = table.getClientArea().height / itemHeight + 2;
        for (int i = top; i < Math.min(top + visible, table.getItemCount()); i++)
        {
            if (table.getItem(i).getData() instanceof Target target)
                result.add(target);
        }
        return result;
    }

    private List<Target> visibleFirst(List<Target> pending)
    {
        return sortVisibleFirst(pending, new HashSet<>(visibleTargets()));
    }

    private static List<Target> sortVisibleFirst(List<Target> pending, Set<Target> onScreen)
    {
        List<Target> ordered = new ArrayList<>(pending.size());
        for (Target target : pending)
        {
            if (onScreen.contains(target))
                ordered.add(target);
        }
        for (Target target : pending)
        {
            if (!onScreen.contains(target))
                ordered.add(target);
        }
        return ordered;
    }

    private static List<Target> groupByFile(List<Target> ordered)
    {
        Map<IFile, List<Target>> byFile = new LinkedHashMap<>();
        for (Target target : ordered)
            byFile.computeIfAbsent(target.file(), key -> new ArrayList<>()).add(target);
        List<Target> result = new ArrayList<>(ordered.size());
        for (List<Target> group : byFile.values())
            result.addAll(group);
        return result;
    }

    private static int lineNumber(String content, int offset)
    {
        int line = 1;
        int limit = Math.min(Math.max(offset, 0), content.length());
        for (int i = 0; i < limit; i++)
        {
            if (content.charAt(i) == '\n')
                line++;
        }
        return line;
    }

    private static int lineStart(String content, int offset)
    {
        int pos = Math.min(Math.max(offset, 0), content.length());
        while (pos > 0 && content.charAt(pos - 1) != '\n' && content.charAt(pos - 1) != '\r')
            pos--;
        return pos;
    }

    private static int lineEnd(String content, int offset)
    {
        int pos = Math.min(Math.max(offset, 0), content.length());
        while (pos < content.length() && content.charAt(pos) != '\n' && content.charAt(pos) != '\r')
            pos++;
        return pos;
    }
}
