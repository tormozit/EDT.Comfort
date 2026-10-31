package tormozit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.internal.text.html.HTMLPrinter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.model.BslContextDefMethod;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.ProposalElement;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.TypeItem;

/**
 * Тип возврата функции конфигурации (issue #877) для строки списка автодополнения
 * ({@link SmartCompletionProposal}) и заголовка подсказки при наведении ({@link BslEditorHoverHook}).
 * EDT пишет его только из комментария «Возвращаемое значение» и не досчитывает — это тип
 * предварительный, показывается с {@code ?} первым элементом ({@code <?, Строка>}), без него —
 * {@code <?>}.
 *
 * <p>Текущая строка запрашивает свой тип сразу; остальные видимые строки — после двух секунд
 * бездействия пользователя. Запрос читает тип одного элемента из индекса или готовых состояний
 * родной модели открытого редактора. Последовательные запросы модуля используют тот же документ
 * и его модель; новая модель не создаётся, накопленные состояния не сбрасываются.
 * Результаты сохраняются для состояния текста ({@link #state}) и точки списка
 * ({@link #variableState}), после получения обновляется показ ({@link #addListener}).
 */
final class FunctionReturnTypes
{
    private static final String LOG = "assist-function-return-type"; //$NON-NLS-1$
    static final String UNKNOWN = "?"; //$NON-NLS-1$

    /**
     * Функция модуля {@code module} с именем {@code name}; {@code exported} — её экспортный образ
     * (строка «Модуль.»): тогда тип есть в индексе сборки ({@link #computeIndex}).
     */
    record Target(URI module, String name, com._1c.g5.v8.dt.mcore.Method exported)
    {
        String key()
        {
            return module + "#" + name.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        }
    }

    /**
     * Тип ({@code <А, Б>}, пустой — не определён); {@code fresh} — рассчитан по текущему состоянию
     * модуля, иначе предварительный (из комментария или рассчитанный по прежнему состоянию).
     */
    record State(String type, boolean fresh)
    {
    }

    /**
     * Тип ({@code <А, Б>}, пустой — не определён) и штамп модуля, по которому получен. Тип из
     * индекса, прочитанный, пока идёт сборка (она обновит индекс), записывается со штампом
     * {@link #UNRELIABLE}: с текущим он не совпадает, и до перечитывания после сборки тип
     * показывается с {@code ?}.
     *
     * <p>Общее правило показа расчётных типов (#877): {@code ?} значит «последний известный тип,
     * сейчас идёт пересчёт, через секунды может смениться» — только пока пересчёт действительно
     * ждёт или идёт ({@link #state}); закончился — тип итоговый, без {@code ?}.
     */
    // Поколение >= 0 — готовая модель редактора, -1 — индекс или итог для закрытого модуля.
    private record Computed(String stamp, String type, boolean linePreserved, int typesGeneration)
    {
        Computed(String stamp, String type)
        {
            this(stamp, type, false, -1);
        }
    }

    /** Окончание штампа типа из индекса, прочитанного во время сборки (см. {@link Computed}). */
    private static final String UNRELIABLE = "~"; //$NON-NLS-1$

    private static String stampOf(String stamp, boolean reliable)
    {
        return reliable ? stamp : stamp + UNRELIABLE;
    }

    /** Сборка ждёт или идёт: индекс сборки может быть старым. */
    private static boolean buildPending()
    {
        var jobs = Job.getJobManager();
        return jobs.find(org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD).length > 0
            || jobs.find(org.eclipse.core.resources.ResourcesPlugin.FAMILY_MANUAL_BUILD).length > 0;
    }

    /**
     * Запрос чтения готовых типов модуля, открытого в редакторе {@code nativeDocument}
     * ({@link #readyFromNative}), в состоянии {@code stamp} на момент запроса.
     */
    private record Request(URI module, IFile file, String stamp, IXtextDocument nativeDocument,
        String method, VariableTarget variable, long queuedAt, long contextRevision, int typesGeneration,
        long returnsRevision)
    {
        Request(URI module, IFile file, String stamp, IXtextDocument nativeDocument,
            String method, VariableTarget variable)
        {
            this(module, file, stamp, nativeDocument, method, variable, System.nanoTime(),
                FunctionReturnTypes.contextRevision(module), BslDocCommentComputedTypes.typesGeneration(),
                semanticRevision());
        }

        String key()
        {
            return variable != null ? variable.key() : module + "#" + method.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        }

        String state()
        {
            return stamp + "@" + (variable != null ? variable.offset() : -1) + "#" + contextRevision //$NON-NLS-1$ //$NON-NLS-2$
                + (variable == null ? "#types=" + typesGeneration : "#returns=" + returnsRevision); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static final Map<String, Computed> RESULTS = new ConcurrentHashMap<>();

    /** Привязка расчётного заголовка к исходному input EDT, независимо от времени создания окна. */
    private record FunctionHint(Target target, java.lang.ref.WeakReference<ITextViewer> viewer, State shown)
    {
    }

    private static final Map<Object, FunctionHint> FUNCTION_HINTS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Переносимые типы для штатного вычислителя: адреса вместо объектов временного ResourceSet. */
    private record SemanticReturn(String stamp, List<URI> types)
    {
    }

    private static final Map<String, SemanticReturn> SEMANTIC_RETURNS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong SEMANTIC_REVISION =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicBoolean NATIVE_HINTS_CHANGED =
        new java.util.concurrent.atomic.AtomicBoolean();

    static long semanticRevision()
    {
        return SEMANTIC_REVISION.get();
    }

    static boolean hasSemanticReturnName(String name)
    {
        if (name == null)
            return false;
        String suffix = "#" + name.toLowerCase(Locale.ROOT);
        return SEMANTIC_RETURNS.keySet().stream().anyMatch(key -> key.endsWith(suffix));
    }

    /** Тот же результат, что у строки функции, но в формате штатного TypesComputer. */
    static List<TypeItem> semanticReturnTypes(com._1c.g5.v8.dt.mcore.Method method)
    {
        Target target = targetOf(method, 0);
        if (target == null)
            return List.of();
        SemanticReturn value = SEMANTIC_RETURNS.get(target.key());
        if (value == null || !value.stamp().equals(currentStamp(target.module())))
            return List.of();
        IFile file = ForeignModuleModel.fileOf(target.module());
        if (file == null || !BslDocCommentComputedTypes.isExtendedTypesEnabled(file.getProject()))
            return List.of();
        List<TypeItem> result = new java.util.ArrayList<>();
        org.eclipse.emf.ecore.EObject context = method instanceof com._1c.g5.v8.dt.bsl.model.BslContextDefMockMethod mock
            && mock.getContext() != null ? mock.getContext() : method;
        for (URI uri : value.types())
        {
            var type = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createType();
            ((InternalEObject)type).eSetProxyURI(uri);
            try
            {
                var resolved = EcoreUtil.resolve(type, context);
                if (!(resolved instanceof TypeItem item) || item.eIsProxy())
                {
                    return List.of();
                }
                result.add(item);
            }
            catch (RuntimeException error)
            {
                return List.of();
            }
        }
        return result;
    }

    private static void rememberSemanticReturn(Target target, String stamp, List<URI> types)
    {
        SemanticReturn value = types.isEmpty() ? null : new SemanticReturn(stamp, List.copyOf(types));
        SemanticReturn old = value != null ? SEMANTIC_RETURNS.put(target.key(), value)
            : SEMANTIC_RETURNS.remove(target.key());
        if (java.util.Objects.equals(value, old))
            return;
        SEMANTIC_REVISION.incrementAndGet();
        REQUEST_STAMPS.keySet().removeIf(key -> key.contains("#var#"));
        Job job = new Job("Комфорт: типы вызовов функции")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                List<IXtextDocument> documents = new java.util.ArrayList<>();
                synchronized (LocalContextInvalidation.documents)
                {
                    for (var document : LocalContextInvalidation.documents.keySet())
                        if (document instanceof IXtextDocument xtext)
                            documents.add(xtext);
                }
                for (IXtextDocument document : documents)
                {
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    try
                    {
                        document.readOnly(resource ->
                        {
                            BslStructureInsertCommentTypes.refreshExternalFunctionTypes(resource, -1);
                            return null;
                        });
                    }
                    catch (RuntimeException error)
                    {
                    }
                }
                NATIVE_HINTS_CHANGED.set(true);
                publish();
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setRule(ForeignModuleModel.SERIAL);
        job.schedule();
    }

    /**
     * Переменная (параметр) метода {@code method} (нижний регистр; пустой — переменная модуля)
     * модуля {@code module}, строка которой показана для точки {@code offset} (модельное смещение
     * точки, в которой открыт список).
     */
    record VariableTarget(URI module, String method, String name, int offset, String fragment)
    {
        String key()
        {
            return module + "#" + method + "#var#" + name.toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Тип переменной в точке {@code offset} по состоянию модуля {@code stamp}. */
    private record VariableComputed(String stamp, int offset, String type, int lineStart, int lineEnd,
        long returnsRevision)
    {
        VariableComputed(String stamp, int offset, String type)
        {
            this(stamp, offset, type, -1, -1, semanticRevision());
        }

        boolean matches(String currentStamp, int point)
        {
            return stamp.equals(currentStamp)
                && returnsRevision == semanticRevision()
                && (lineStart >= 0 ? point >= lineStart && point <= lineEnd : offset == point);
        }
    }

    private static final Map<String, VariableComputed> VARIABLE_RESULTS = new ConcurrentHashMap<>();
    /** Возвраты, от которых зависит показанный тип переменной; ожидающий источник делает тип предварительным. */
    private static final Map<String, List<Target>> VARIABLE_SOURCES = new ConcurrentHashMap<>();

    static void rememberVariableSources(String key, java.util.Collection<Target> sources)
    {
        VARIABLE_SOURCES.put(key, List.copyOf(sources));
    }

    private static boolean variableSourcesPending(String key)
    {
        for (Target source : VARIABLE_SOURCES.getOrDefault(key, List.of()))
        {
            State result = state(source);
            if (result != null && !result.fresh())
                return true;
        }
        return false;
    }
    /**
     * Предварительные типы — из «Возвращаемое значение» комментария функций без готового типа,
     * разбором комментария в родной модели редактора ({@link #readyFromNative}): EDT в строке тип
     * часто не пишет вовсе.
     */
    private static final Map<String, Computed> COMMENT_TYPES = new ConcurrentHashMap<>();
    /** Элемент → состояние, для которого его готовый тип уже запрошен. */
    private static final Map<String, String> REQUEST_STAMPS = new ConcurrentHashMap<>();

    /**
     * Готовый тип функции или переменной ({@code offset >= 0}), прочитанный под блокировкой документа
     * редактора, в котором есть ещё не загруженные ссылки на типы ({@link URI} среди {@code parts},
     * остальное — имена). Ссылки загружаются после снятия блокировки в своём наборе ресурсов
     * ({@link #resolvePending}): модель редактора не меняется, ввод не ждёт.
     */
    private record Pending(String key, List<Object> parts, String stamp, int offset)
    {
    }

    /** Имя типа по адресу ссылки на него (пустое — не загрузилась); адреса платформенных типов неизменны. */
    private static final Map<URI, String> PROXY_NAMES = new ConcurrentHashMap<>();

    /** Запрос типа экспортной функции из индекса сборки для состояния модуля {@code stamp}. */
    private record IndexRequest(Target target, IFile file, String stamp, boolean reliable, long queuedAt)
    {
        IndexRequest(Target target, IFile file, String stamp, boolean reliable)
        {
            this(target, file, stamp, reliable, System.nanoTime());
        }
    }

    /** Экспортные функции, ожидающие чтения индекса; самая быстрая очередь, текущая — в голове. */
    private static final java.util.Deque<IndexRequest> INDEX_QUEUE = new java.util.ArrayDeque<>();
    /** Функция → штамп модуля, для которого индекс уже запрошен. */
    private static final Map<String, String> INDEX_STAMPS = new ConcurrentHashMap<>();
    /**
     * Элементы, ожидающие чтения готового типа из модели редактора. Текущий элемент —
     * в голове очереди. Под монитором самой очереди.
     */
    private static final java.util.Deque<Request> QUEUE = new java.util.ArrayDeque<>();
    /** Текущий элемент имеет приоритет перед обеими очередями фонового чтения. */
    private static volatile String priorityKey;
    /** Перерисовка показов после новых типов; {@code false} — слушатель больше не нужен. UI-поток. */
    private static final List<BooleanSupplier> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Один элемент за запуск. Правило {@link ForeignModuleModel#SERIAL} сохраняет
     * последовательность чтения общей модели, не удерживая очередь между элементами.
     */
    private static final Job WORKER = new Job("Комфорт: тип функции") //$NON-NLS-1$
    {
        @Override
        protected IStatus run(IProgressMonitor monitor)
        {
            IndexRequest index = null;
            Request request = null;
            synchronized (QUEUE)
            {
                for (IndexRequest candidate : INDEX_QUEUE)
                    if (candidate.target().key().equals(priorityKey))
                    {
                        index = candidate;
                        break;
                    }
                if (index != null)
                    INDEX_QUEUE.remove(index);
                else
                {
                    for (Request candidate : QUEUE)
                        if (candidate.key().equals(priorityKey))
                        {
                            request = candidate;
                            break;
                        }
                    if (request != null)
                        QUEUE.remove(request);
                }
                if (index == null && request == null)
                {
                    index = INDEX_QUEUE.pollFirst();
                    if (index == null)
                        request = QUEUE.pollFirst();
                }
            }
            try
            {
                if (index != null)
                    computeIndex(index);
                else if (request != null)
                    compute(request);
            }
            finally
            {
                boolean more;
                synchronized (QUEUE)
                {
                    more = !QUEUE.isEmpty() || !INDEX_QUEUE.isEmpty();
                }
                if (more)
                    schedule();
            }
            return Status.OK_STATUS;
        }
    };

    static
    {
        WORKER.setSystem(true);
        WORKER.setPriority(Job.INTERACTIVE);
        WORKER.setRule(ForeignModuleModel.SERIAL);
    }

    private FunctionReturnTypes()
    {
    }

    /** Перерисовка строки не запускает чтение типов: запросами управляет активный список. */
    static void rowShown(SmartCompletionProposal proposal)
    {
        if (Display.getCurrent() == null)
            return;
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        Object popup = assistant != null ? ContentAssistPopupSync.getPopupObject(assistant) : null;
        org.eclipse.swt.widgets.Table table = ContentAssistPopupSync.getProposalTable(popup);
        if (table == null || table.isDisposed())
            return;
        if (table.getData(TypeDemandSession.KEY) == null)
            new TypeDemandSession(assistant, table);
        List<org.eclipse.jface.text.contentassist.ICompletionProposal> rows =
            ContentAssistPopupSync.visibleProposals(assistant);
        int selected = table.getSelectionIndex();
        if (rows != null && selected >= 0 && selected < rows.size() && rows.get(selected) == proposal)
            proposal.requestRowTypes(true);
        else if (table.getData(TypeDemandSession.KEY) instanceof TypeDemandSession session)
            session.ensureTimer();
    }

    /** Текущая строка — сразу; остальные фактически видимые строки — через 2 с без ввода. */
    private static final class TypeDemandSession
    {
        static final String KEY = "comfort.typeDemand"; //$NON-NLS-1$
        private static final int IDLE_MS = 2000;
        private static final int[] INPUT_EVENTS = {
            org.eclipse.swt.SWT.KeyDown, org.eclipse.swt.SWT.MouseDown, org.eclipse.swt.SWT.MouseMove,
            org.eclipse.swt.SWT.MouseWheel, org.eclipse.swt.SWT.Selection, org.eclipse.swt.SWT.Modify };

        private final ContentAssistant assistant;
        private final org.eclipse.swt.widgets.Table table;
        private final Display display;
        private final Runnable timer = this::requestVisible;
        private final org.eclipse.swt.widgets.Listener input = event -> postpone();
        private final org.eclipse.swt.widgets.Listener closing = event -> close();
        private boolean closed;
        private boolean timerScheduled;
        private long idleAt;

        TypeDemandSession(ContentAssistant assistant, org.eclipse.swt.widgets.Table table)
        {
            this.assistant = assistant;
            this.table = table;
            display = table.getDisplay();
            table.setData(KEY, this);
            for (int event : INPUT_EVENTS)
                display.addFilter(event, input);
            table.addListener(org.eclipse.swt.SWT.Dispose, closing);
            table.getShell().addListener(org.eclipse.swt.SWT.Hide, closing);
            postpone();
        }

        private void postpone()
        {
            if (closed)
                return;
            discardBackgroundRequests();
            display.timerExec(-1, timer);
            timerScheduled = false;
            idleAt = System.nanoTime() + IDLE_MS * 1_000_000L;
            ensureTimer();
        }

        /** Новый ввод отменяет ещё не начатые запросы остальных строк, сохраняя их готовый кэш. */
        private static void discardBackgroundRequests()
        {
            synchronized (QUEUE)
            {
                var requests = QUEUE.iterator();
                while (requests.hasNext())
                {
                    Request request = requests.next();
                    if (!request.key().equals(priorityKey))
                    {
                        requests.remove();
                        REQUEST_STAMPS.remove(request.key(), request.state());
                    }
                }
                var indexes = INDEX_QUEUE.iterator();
                while (indexes.hasNext())
                {
                    IndexRequest request = indexes.next();
                    if (!request.target().key().equals(priorityKey))
                    {
                        indexes.remove();
                        INDEX_STAMPS.remove(request.target().key(), stampOf(request.stamp(), request.reliable()));
                    }
                }
            }
        }

        private void ensureTimer()
        {
            if (closed || timerScheduled)
                return;
            long remaining = idleAt - System.nanoTime();
            timerScheduled = true;
            display.timerExec((int)Math.max(1L, (remaining + 999_999L) / 1_000_000L), timer);
        }

        private void requestVisible()
        {
            timerScheduled = false;
            if (closed || table.isDisposed() || !ContentAssistPopupSync.isPopupVisible(assistant))
            {
                close();
                return;
            }
            List<org.eclipse.jface.text.contentassist.ICompletionProposal> rows =
                ContentAssistPopupSync.visibleProposals(assistant);
            if (rows == null)
                return;
            var area = table.getClientArea();
            int requested = 0;
            for (int i = table.getTopIndex(); i < table.getItemCount() && i < rows.size(); i++)
            {
                var bounds = table.getItem(i).getBounds();
                if (bounds.y >= area.y + area.height)
                    break;
                if (bounds.y + bounds.height <= area.y || i == table.getSelectionIndex())
                    continue;
                if (rows.get(i) instanceof SmartCompletionProposal proposal)
                {
                    proposal.requestRowTypes(false);
                    requested++;
                }
            }
            log("visible.requests rows=" + requested + " idleMs=" + IDLE_MS); //$NON-NLS-1$ //$NON-NLS-2$
            traceVisibleRows("idle"); //$NON-NLS-1$
        }

        private void traceVisibleRows(String phase)
        {
            if (table.isDisposed())
                return;
            List<org.eclipse.jface.text.contentassist.ICompletionProposal> rows =
                ContentAssistPopupSync.visibleProposals(assistant);
            var area = table.getClientArea();
            for (int i = table.getTopIndex(); i < table.getItemCount(); i++)
            {
                var item = table.getItem(i);
                var bounds = item.getBounds();
                if (bounds.y >= area.y + area.height)
                    break;
                Object proposal = rows != null && i < rows.size() ? rows.get(i) : null;
                Global.tempLog("assist-type-display", "table phase=" + phase + " index=" + i //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " proposal=" + System.identityHashCode(proposal) + " text=" + item.getText()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private void close()
        {
            if (closed)
                return;
            closed = true;
            discardBackgroundRequests();
            display.timerExec(-1, timer);
            for (int event : INPUT_EVENTS)
                display.removeFilter(event, input);
            if (!table.isDisposed())
            {
                table.setData(KEY, null);
                table.removeListener(org.eclipse.swt.SWT.Dispose, closing);
                if (!table.getShell().isDisposed())
                    table.getShell().removeListener(org.eclipse.swt.SWT.Hide, closing);
            }
        }
    }

    /**
     * Функция конфигурации объекта строки EDT или элемента под указателем: метод модуля, его
     * экспортный образ, {@code ProposalElement} или {@code ParamSet} с ними; иначе {@code null}.
     * Прокси не разрешаются (как {@code edtDocumentationObjectProblem}).
     */
    static Target targetOf(Object info, int depth)
    {
        try
        {
            return availableTargetOf(info, depth);
        }
        catch (IllegalStateException error)
        {
            // BM-объект предложения мог быть удалён после получения списка.
            if (!"Object is removed".equals(error.getMessage())) //$NON-NLS-1$
                throw error;
            Global.tempLogException("assist-removed-object", "functionTarget infoClass=" //$NON-NLS-1$ //$NON-NLS-2$
                + (info == null ? null : info.getClass().getName()) + " depth=" + depth, error); //$NON-NLS-1$
            return null;
        }
    }

    private static Target availableTargetOf(Object info, int depth)
    {
        if (depth > 3)
            return null;
        if (info instanceof ProposalElement && info instanceof InternalEObject element)
        {
            Object value = element.eGet(BslPackage.PROPOSAL_ELEMENT__LIST, false, false);
            if (!(value instanceof InternalEList<?> list))
                return null;
            var entries = list.basicIterator();
            while (entries.hasNext())
            {
                Target target = targetOf(entries.next(), depth + 1);
                if (target != null)
                    return target;
            }
            return null;
        }
        if (info instanceof ParamSet && info instanceof InternalEObject set)
            return targetOf(set.eInternalContainer(), depth + 1);
        if (info instanceof com._1c.g5.v8.dt.bsl.model.Method method)
        {
            if (!(method instanceof com._1c.g5.v8.dt.bsl.model.Function) || method.eIsProxy()
                || method.getName() == null)
                return null;
            Resource resource = method.eResource();
            if (resource == null || resource.getURI() == null)
                return null;
            return new Target(resource.getURI().trimFragment(), method.getName(),
                method.isExport() ? exportedLookup(method) : null);
        }
        if (info instanceof BslContextDefMethod exported && !exported.eIsProxy()
            && exported.isRetVal() && exported.getSourceUri() != null && exported.getName() != null)
            return new Target(exported.getSourceUri().trimFragment(), exported.getName(),
                exportedLookup(exported.getName(), exported.getSourceUri(), exported.environments()));
        return null;
    }

    private static com._1c.g5.v8.dt.mcore.Method exportedLookup(com._1c.g5.v8.dt.bsl.model.Method method)
    {
        return exportedLookup(method.getName(), EcoreUtil.getURI(method), method.environments());
    }

    /**
     * Запрос к индексу сборки ({@code ExportMethodTypeProvider.getMethodType}) — всегда отдельный
     * экспортный образ, не связанный ни с одной моделью: {@code getMethodType} меняет переданный
     * метод ({@code setRetVal}), а у {@code BslContextDefMockMethod} разрешает типы через его
     * контекст — узел модели редактора. Вызов над объектом модели редактора из фона, без
     * блокировки его документа, портил эту модель: в ней переставали разрешаться обращения через
     * точку и пустела подсказка при наведении (#877, 23.10.2026). Ключ индекса (по байткоду bsl
     * 28.0.1) — FQN модуля из {@code getSourceUri}, имя и {@code environments().toString()}; без
     * записи возвращается собственный (пустой) {@code getRetValType}.
     */
    private static com._1c.g5.v8.dt.mcore.Method exportedLookup(String name, URI sourceUri,
        com._1c.g5.v8.dt.mcore.util.Environments environments)
    {
        try
        {
            com._1c.g5.v8.dt.bsl.model.BslContextDefMethod lookup =
                com._1c.g5.v8.dt.bsl.model.BslContextDefFactory.eINSTANCE.createBslContextDefMethod();
            lookup.setName(name);
            lookup.setSourceUri(sourceUri);
            lookup.setEnvironments(environments);
            lookup.setRetVal(true);
            return lookup;
        }
        catch (RuntimeException error)
        {
            log("error lookup method=" + name + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /** Адреса ссылок на типы свойств, загрузка которых поставлена ({@link #propertyState}). */
    private static final Set<URI> PROPERTY_LOADS = ConcurrentHashMap.newKeySet();

    /**
     * Тип свойства строки EDT ({@code mcore.Property}: свойства глобального контекста — общие
     * модули и т.п., свойства через точку) — его готовые типы ({@code Property.getTypes}, их же
     * показывает подсказка EDT), без досчёта; {@code null} — не свойство или показывать нечего.
     * Ссылки на типы, ещё не загруженные, загружаются в фоне ({@link #loadProxyNames}); до того —
     * {@code ?}. UI-поток.
     */
    static State propertyState(Object info)
    {
        return propertyState(info, false);
    }

    static void requestProperty(Object info)
    {
        propertyState(info, true);
    }

    private static State propertyState(Object info, boolean request)
    {
        if (!(info instanceof com._1c.g5.v8.dt.mcore.Property property))
            return null;
        List<Object> parts = new java.util.ArrayList<>();
        Set<URI> unloaded = new LinkedHashSet<>();
        for (TypeItem item : property.getTypes())
        {
            Object part = partOf(item);
            // Ссылка, которая не загрузилась, — пустое имя: типа нет.
            if (part == null || "".equals(part)) //$NON-NLS-1$
                return null;
            if (part instanceof URI uri)
                unloaded.add(uri);
            parts.add(part);
        }
        if (parts.isEmpty())
            return null;
        if (unloaded.isEmpty())
        {
            // «ОбщийМодуль.Мурзик» у свойства «Мурзик» — хвост повторяет имя строки; как подсказка
            // EDT, показываем «ОбщийМодуль».
            Set<String> names = new LinkedHashSet<>();
            for (Object part : parts)
                names.add(withoutOwnName((String)part, property));
            return new State(formatTypeNames(names), true);
        }
        if (request)
            loadPropertyTypes(unloaded);
        return new State("", false); //$NON-NLS-1$
    }

    private static String withoutOwnName(String type, com._1c.g5.v8.dt.mcore.Property property)
    {
        for (String own : new String[] { property.getNameRu(), property.getName() })
        {
            if (own != null && !own.isEmpty() && type.length() > own.length() + 1
                && type.regionMatches(true, type.length() - own.length(), own, 0, own.length())
                && type.charAt(type.length() - own.length() - 1) == '.')
                return type.substring(0, type.length() - own.length() - 1);
        }
        return type;
    }

    /** Фоновая загрузка ссылок на типы свойств — в наборе ресурсов проекта редактора списка. */
    private static void loadPropertyTypes(Set<URI> unloaded)
    {
        unloaded.removeIf(uri -> !PROPERTY_LOADS.add(uri));
        if (unloaded.isEmpty())
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        BslXtextEditor editor = reloader != null ? reloader.getBslEditor() : null;
        IFile file = editor != null && editor.getEditorInput() != null
            ? editor.getEditorInput().getAdapter(IFile.class) : null;
        if (file == null)
        {
            PROPERTY_LOADS.removeAll(unloaded);
            return;
        }
        URI module = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        Job job = new Job("Комфорт: типы свойств") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                long start = System.nanoTime();
                int count = unloaded.size();
                loadProxyNames(unloaded, module, file.getProject());
                log("property proxies loaded=" + count //$NON-NLS-1$
                    + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
                publish();
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setRule(ForeignModuleModel.SERIAL);
        job.schedule();
    }

    /** Адрес строки переживает отсоединение её AST при переразборе; сам AST здесь не удерживается. */
    private static final Map<ProposalElement, VariableTarget> VARIABLE_TARGETS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Снять только адреса переменных сразу после штатного compute, до сохранения списка
     * и следующего переразбора. Типы здесь не читаются и фоновые запросы не создаются.
     */
    static void rememberVariableTargets(org.eclipse.jface.text.contentassist.ICompletionProposal[] proposals)
    {
        if (proposals == null)
            return;
        for (var proposal : proposals)
        {
            if (proposal instanceof org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal configurable
                && Global.getField(configurable, "additionalProposalInfo") instanceof ProposalElement info) //$NON-NLS-1$
                variableTargetOf(info);
        }
    }

    /**
     * Переменная строки EDT: {@code ProposalElement} с {@code FantomVariableOccurrence}
     * (переменная и точка, где открыт список; так строит строку
     * {@code BslProposalProvider.createProposalsByDeclarementVariables}); иначе {@code null}.
     * Прокси не разрешаются.
     */
    static VariableTarget variableTargetOf(Object info)
    {
        if (!(info instanceof ProposalElement && info instanceof InternalEObject element))
            return null;
        VariableTarget remembered = VARIABLE_TARGETS.get((ProposalElement)info);
        if (remembered != null)
            return remembered;
        Object value = element.eGet(BslPackage.PROPOSAL_ELEMENT__LIST, false, false);
        if (!(value instanceof InternalEList<?> list))
        {
            return null;
        }
        var entries = list.basicIterator();
        while (entries.hasNext())
        {
            Object entry = entries.next();
            if (!(entry instanceof com._1c.g5.v8.dt.bsl.model.FantomVariableOccurrence occurrence))
                continue;
            com._1c.g5.v8.dt.bsl.model.Variable variable = occurrence.getVariable();
            if (variable == null || variable.eIsProxy() || variable.getName() == null)
            {
                return null;
            }
            Resource resource = variable.eResource();
            if (resource == null || resource.getURI() == null)
            {
                return null;
            }
            com._1c.g5.v8.dt.bsl.model.Method method =
                org.eclipse.xtext.EcoreUtil2.getContainerOfType(variable, com._1c.g5.v8.dt.bsl.model.Method.class);
            String methodName = method != null && method.getName() != null
                ? method.getName().toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
            VariableTarget target = new VariableTarget(resource.getURI().trimFragment(), methodName, variable.getName(),
                occurrence.getOffset(), resource.getURIFragment(variable));
            VARIABLE_TARGETS.put((ProposalElement)info, target);
            return target;
        }
        return null;
    }

    /**
     * Конечный результат для текущего текста и точки показывается без {@code ?}, в том числе
     * во время паузы. Если такого результата ещё нет, тип ожидает чтения: {@code ?} показывается
     * и до постановки запроса в очередь, вместе с прежним типом, если он известен.
     * Вне UI — только чтение, без запросов.
     */
    static State variableState(VariableTarget target)
    {
        if (target == null)
            return null;
        String stamp = currentStamp(target.module());
        rememberLineContextKey(target.module(), target.key());
        VariableComputed computed = VARIABLE_RESULTS.get(target.key());
        // #region temp #877 — что получает строка переменной
        log("  show key=" + target.key() + " offset=" + target.offset() + " stamp=" + stamp //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " computed=" + computed); //$NON-NLS-1$
        // #endregion
        if (computed != null && computed.matches(stamp, target.offset()))
        {
            boolean pending = variableSourcesPending(target.key());
            return new State(computed.type(), !pending);
        }
        if (stamp.isEmpty())
            return new State("", true); //$NON-NLS-1$
        return new State(computed != null ? computed.type() : "", false); //$NON-NLS-1$
    }

    static void requestVariable(VariableTarget target, boolean first)
    {
        if (target == null)
            return;
        IFile file = ForeignModuleModel.fileOf(target.module());
        String stamp = currentStamp(target.module());
        if (file == null || stamp == null || stamp.isEmpty())
            return;
        if (first)
            priorityKey = target.key();
        VariableComputed computed = VARIABLE_RESULTS.get(target.key());
        if (computed != null && computed.matches(stamp, target.offset()))
        {
            return;
        }
        Request request = new Request(target.module(), file, stamp, ForeignModuleModel.openDocument(file),
            target.method(), target);
        if (request.nativeDocument() == null)
            finishUnknown(request);
        else
            enqueue(request, first);
    }

    /**
     * Тип функции для показа или {@code null} — показывать нечего: ничего не пересчитывается и
     * итогового нет, строка и подсказка остаются штатными. Итоговый ({@code fresh}) — полученный
     * для текущего состояния модуля (индекс сборки или готовые типы модели редактора). Пока
     * пересчёт ждёт или идёт — предварительный, с {@code ?}: последний известный, иначе из
     * «Возвращаемое значение» комментария. Показ строки, подсказки; вне UI — только чтение, без
     * запросов.
     */
    static State state(Target target)
    {
        if (target == null)
            return null;
        String stamp = currentStamp(target.module());
        if (target.exported() == null)
            rememberLineContextKey(target.module(), target.key());
        Computed computed = RESULTS.get(target.key());
        if (functionCurrent(computed, stamp, target.module()))
            return new State(computed.type(), true);
        // Конечный результат уже проверен выше. Только при его отсутствии пауза — ожидание с «?».
        if (stamp.isEmpty())
            return new State("", true); //$NON-NLS-1$
        if (computed != null && !computed.type().isEmpty())
            return new State(computed.type(), false);
        Computed comment = COMMENT_TYPES.get(target.key());
        return new State(comment != null ? comment.type() : "", false); //$NON-NLS-1$
    }

    /** Имена типов раздела «Возвращаемое значение» как в тексте комментария: {@code <А, Б>}. */
    private static String commentType(
        com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment comment)
    {
        if (comment == null || comment.getReturnSection() == null)
            return ""; //$NON-NLS-1$
        Set<String> names = new LinkedHashSet<>();
        for (com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection section
            : comment.getReturnSection().getReturnTypes())
        {
            for (com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection.TypeDefinition definition
                : section.getTypeDefinitions())
            {
                String name = definition.getTypeName();
                if (name != null && !name.isBlank())
                    names.add(SmartContentAssistProcessor.localizeAssistTypeSegment(name.trim()));
            }
        }
        return formatTypeNames(names);
    }

    /**
     * Перерисовка показа после новых типов (UI-поток). Слушатель, вернувший {@code false},
     * снимается — так держатель без явного снятия не удерживается навсегда.
     */
    static void addListener(BooleanSupplier listener)
    {
        LISTENERS.add(listener);
    }

    /** Штамп модуля, проверенный недавно: показ строк не обходит редакторы на каждой строке. */
    private record StampCheck(String stamp, long checkedAt)
    {
    }

    private static final long STAMP_CHECK_TTL_NS = 200_000_000L;
    private static final Map<URI, StampCheck> STAMP_CHECKS = new ConcurrentHashMap<>();

    /**
     * Модуль → документ его открытого редактора, найденный UI-потоком ({@code null} внутри ссылки —
     * не открыт). Для штампа вне UI: обход редакторов там невозможен, а штамп документа — простое
     * чтение поля.
     */
    private static final Map<URI, java.lang.ref.WeakReference<IXtextDocument>> KNOWN_DOCUMENTS =
        new ConcurrentHashMap<>();

    /** Один слушатель на документ; значение не удерживает сам документ после закрытия редактора. */
    private static final Map<IXtextDocument, LocalContextLineCache> LOCAL_CONTEXT_LINES =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final Map<URI, Long> CONTEXT_REVISIONS = new ConcurrentHashMap<>();
    private static final Map<URI, Set<String>> LINE_CONTEXT_KEYS = new ConcurrentHashMap<>();

    /** С первой отрисовки, ещё до первой правки строки: её готовые типы уже могут устареть. */
    private static void rememberLineContextKey(URI module, String key)
    {
        if (Display.getCurrent() == null)
            return;
        var known = KNOWN_DOCUMENTS.get(module);
        var viewer = ContentAssistSessionReloader.getActiveViewer();
        if (known != null && known.get() != null && viewer != null && viewer.getDocument() == known.get())
            LINE_CONTEXT_KEYS.computeIfAbsent(module, unused -> ConcurrentHashMap.newKeySet()).add(key);
    }

    private static long contextRevision(URI module)
    {
        return CONTEXT_REVISIONS.computeIfAbsent(module, key -> 0L);
    }

    /** Правки других модулей обесценивают также тип получателя и запас его членов. */
    static long localContextRevision(org.eclipse.jface.text.IDocument document)
    {
        synchronized (LocalContextInvalidation.documents)
        {
            URI module = LocalContextInvalidation.modules.get(document);
            return module != null ? contextRevision(module) : 0L;
        }
    }

    static void installLocalContextInvalidation()
    {
        LocalContextInvalidation.install();
    }

    static void stopLocalContextInvalidation()
    {
        LocalContextInvalidation.stop();
    }

    /** EDT использует и документы, не зарегистрированные в FileBuffers. */
    static void observeLocalContextEditor(BslXtextEditor editor)
    {
        var input = editor.getEditorInput();
        IFile file = input != null ? input.getAdapter(IFile.class) : null;
        var document = editor.getDocument();
        Global.tempLog("assist-local-context-invalidation", "editor title=" + editor.getTitle() //$NON-NLS-1$ //$NON-NLS-2$
            + " input=" + (input == null ? null : input.getClass().getName()) + " file=" + file //$NON-NLS-1$ //$NON-NLS-2$
            + " document=" + (document == null ? null : document.getClass().getName())); //$NON-NLS-1$
        if (file != null && document != null)
            LocalContextInvalidation.observe(URI.createPlatformResourceURI(file.getFullPath().toString(), true), document);
    }

    /** Только результаты, перенесённые через правку той же строки, теряют актуальность. */
    private static void otherModuleChanged(URI changedModule)
    {
        int[] invalidated = { 0 };
        for (URI module : CONTEXT_REVISIONS.keySet())
        {
            if (module.equals(changedModule))
                continue;
            long revision = CONTEXT_REVISIONS.merge(module, 1L, Long::sum);
            String prefix = module + "#"; //$NON-NLS-1$
            String suffix = "!dependency-" + revision; //$NON-NLS-1$
            Set<String> lineKeys = LINE_CONTEXT_KEYS.getOrDefault(module, java.util.Collections.emptySet());
            VARIABLE_RESULTS.replaceAll((key, value) ->
            {
                if (!key.startsWith(prefix) || value.lineStart() < 0 && !lineKeys.contains(key))
                    return value;
                invalidated[0]++;
                REQUEST_STAMPS.remove(key);
                Global.tempLog("assist-local-context-invalidation", "variable key=" + key //$NON-NLS-1$ //$NON-NLS-2$
                    + " oldStamp=" + value.stamp() + " shown=" + lineKeys.contains(key) + " changed=" + changedModule); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return new VariableComputed(suffix, value.offset(), value.type());
            });
            RESULTS.replaceAll((key, value) ->
            {
                if (!key.startsWith(prefix) || !value.linePreserved() && !lineKeys.contains(key))
                    return value;
                invalidated[0]++;
                REQUEST_STAMPS.remove(key);
                INDEX_STAMPS.remove(key);
                Global.tempLog("assist-local-context-invalidation", "function key=" + key //$NON-NLS-1$ //$NON-NLS-2$
                    + " oldStamp=" + value.stamp() + " shown=" + lineKeys.contains(key) + " changed=" + changedModule); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return new Computed(suffix, value.type());
            });
        }
        Global.tempLog("assist-local-context-invalidation", "changed=" + changedModule + " invalidated=" + invalidated[0]); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (invalidated[0] > 0)
            publish();
    }

    /** Правки открытых буферов, в том числе без автодополнения, и изменения файлов вне редактора. */
    private static final class LocalContextInvalidation implements org.eclipse.core.filebuffers.IFileBufferListener,
        org.eclipse.core.resources.IResourceChangeListener
    {
        private static final LocalContextInvalidation INSTANCE = new LocalContextInvalidation();
        private static final Map<org.eclipse.jface.text.IDocument, org.eclipse.jface.text.IDocumentListener> documents =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final Map<org.eclipse.jface.text.IDocument, URI> modules = new java.util.WeakHashMap<>();

        static void install()
        {
            var manager = org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager();
            manager.addFileBufferListener(INSTANCE);
            for (var buffer : manager.getFileBuffers())
                INSTANCE.bufferCreated(buffer);
            org.eclipse.core.resources.ResourcesPlugin.getWorkspace().addResourceChangeListener(INSTANCE,
                org.eclipse.core.resources.IResourceChangeEvent.POST_CHANGE);
            Global.tempLog("assist-local-context-invalidation", "installed"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        static void stop()
        {
            org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager().removeFileBufferListener(INSTANCE);
            org.eclipse.core.resources.ResourcesPlugin.getWorkspace().removeResourceChangeListener(INSTANCE);
            synchronized (documents)
            {
                documents.forEach((document, listener) -> document.removeDocumentListener(listener));
                documents.clear();
                modules.clear();
            }
        }

        @Override
        public void bufferCreated(org.eclipse.core.filebuffers.IFileBuffer buffer)
        {
            if (!(buffer instanceof org.eclipse.core.filebuffers.ITextFileBuffer text) || buffer.getLocation() == null
                || !"bsl".equalsIgnoreCase(buffer.getLocation().getFileExtension())) //$NON-NLS-1$
                return;
            var file = org.eclipse.core.filebuffers.FileBuffers.getWorkspaceFileAtLocation(buffer.getLocation());
            if (file == null)
                return;
            URI module = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
            observe(module, text.getDocument());
        }

        static void observe(URI module, org.eclipse.jface.text.IDocument document)
        {
            synchronized (documents)
            {
                if (document == null || documents.containsKey(document))
                    return;
                org.eclipse.jface.text.IDocumentListener listener = new org.eclipse.jface.text.IDocumentListener()
                {
                    @Override
                    public void documentAboutToBeChanged(org.eclipse.jface.text.DocumentEvent event) {}

                    @Override
                    public void documentChanged(org.eclipse.jface.text.DocumentEvent event)
                    {
                        otherModuleChanged(module);
                    }
                };
                documents.put(document, listener);
                modules.put(document, module);
                contextRevision(module);
                document.addDocumentListener(listener);
                Global.tempLog("assist-local-context-invalidation", "observe module=" + module //$NON-NLS-1$ //$NON-NLS-2$
                    + " document=" + document.getClass().getName() + "@" + System.identityHashCode(document)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        @Override
        public void bufferDisposed(org.eclipse.core.filebuffers.IFileBuffer buffer)
        {
            if (buffer instanceof org.eclipse.core.filebuffers.ITextFileBuffer text)
            {
                synchronized (documents)
                {
                    var listener = documents.remove(text.getDocument());
                    modules.remove(text.getDocument());
                    if (listener != null)
                        text.getDocument().removeDocumentListener(listener);
                }
            }
        }

        @Override
        public void resourceChanged(org.eclipse.core.resources.IResourceChangeEvent event)
        {
            if (event.getDelta() == null)
                return;
            try
            {
                event.getDelta().accept(delta ->
                {
                    if (!(delta.getResource() instanceof IFile file))
                        return true;
                    if ("bsl".equalsIgnoreCase(file.getFileExtension()) //$NON-NLS-1$
                        && (delta.getKind() != org.eclipse.core.resources.IResourceDelta.CHANGED
                            || (delta.getFlags() & org.eclipse.core.resources.IResourceDelta.CONTENT) != 0))
                        otherModuleChanged(URI.createPlatformResourceURI(file.getFullPath().toString(), true));
                    return false;
                });
            }
            catch (org.eclipse.core.runtime.CoreException error)
            {
                Global.tempLogException("assist-local-context-invalidation", "resource.failed", error); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        @Override public void bufferContentAboutToBeReplaced(org.eclipse.core.filebuffers.IFileBuffer buffer) {}
        @Override public void bufferContentReplaced(org.eclipse.core.filebuffers.IFileBuffer buffer)
        {
            bufferDisposed(buffer);
            bufferCreated(buffer);
        }
        @Override public void stateChanging(org.eclipse.core.filebuffers.IFileBuffer buffer) {}
        @Override public void dirtyStateChanged(org.eclipse.core.filebuffers.IFileBuffer buffer, boolean dirty) {}
        @Override public void stateValidationChanged(org.eclipse.core.filebuffers.IFileBuffer buffer, boolean validated) {}
        @Override public void underlyingFileMoved(org.eclipse.core.filebuffers.IFileBuffer buffer, org.eclipse.core.runtime.IPath path)
        {
            bufferDisposed(buffer);
            bufferCreated(buffer);
        }
        @Override public void underlyingFileDeleted(org.eclipse.core.filebuffers.IFileBuffer buffer) {}
        @Override public void stateChangeFailed(org.eclipse.core.filebuffers.IFileBuffer buffer) {}
    }

    /** Конечные типы локального контекста сохраняются на время редактирования одной строки. */
    private static final class LocalContextLineCache implements org.eclipse.jface.text.IDocumentListener
    {
        private final URI module;
        private final IFile file;
        private int editingLine = -1;
        private int lineStart;
        private int lineEnd;
        private String beforeStamp;
        private boolean preserve;

        LocalContextLineCache(URI module, IFile file, IXtextDocument document)
        {
            this.module = module;
            this.file = file;
            contextRevision(module);
            ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
            var viewer = reloader != null ? reloader.getViewer() : null;
            if (viewer != null && viewer.getDocument() == document)
            {
                try
                {
                    editingLine = document.getLineInformationOfOffset(viewer.getSelectedRange().x).getOffset();
                }
                catch (org.eclipse.jface.text.BadLocationException error)
                {
                    Global.tempLog("assist-local-context", "initial point error=" + error); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            document.addDocumentListener(this);
        }

        @Override
        public void documentAboutToBeChanged(org.eclipse.jface.text.DocumentEvent event)
        {
            preserve = false;
            beforeStamp = ForeignModuleModel.stamp(file, (IXtextDocument)event.getDocument());
            try
            {
                var line = event.getDocument().getLineInformationOfOffset(event.getOffset());
                lineStart = line.getOffset();
                lineEnd = lineStart + line.getLength();
                String inserted = event.getText();
                boolean singleLine = event.getOffset() + event.getLength() <= lineEnd
                    && (inserted == null || inserted.indexOf('\n') < 0 && inserted.indexOf('\r') < 0);
                preserve = singleLine && lineStart == editingLine;
                editingLine = singleLine ? lineStart : -1;
            }
            catch (org.eclipse.jface.text.BadLocationException error)
            {
                editingLine = -1;
                Global.tempLog("assist-local-context", "edit point error=" + error); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Global.tempLog("assist-local-context", "before module=" + module + " stamp=" + beforeStamp //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " line=" + lineStart + " preserve=" + preserve); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void documentChanged(org.eclipse.jface.text.DocumentEvent event)
        {
            String afterStamp = ForeignModuleModel.stamp(file, (IXtextDocument)event.getDocument());
            if (!LocalContextInvalidation.documents.containsKey(event.getDocument()))
                otherModuleChanged(module);
            STAMP_CHECKS.put(module, new StampCheck(afterStamp, System.nanoTime()));
            int[] retained = { 0 };
            if (!preserve)
                LINE_CONTEXT_KEYS.remove(module);
            if (preserve)
            {
                int newEnd = lineEnd - event.getLength() + (event.getText() != null ? event.getText().length() : 0);
                String prefix = module + "#"; //$NON-NLS-1$
                VARIABLE_RESULTS.replaceAll((key, value) ->
                {
                    boolean sameLine = value.lineStart() >= 0 ? value.lineStart() == lineStart
                        : value.offset() >= lineStart && value.offset() <= lineEnd;
                    if (!key.startsWith(prefix) || !value.stamp().equals(beforeStamp) || !sameLine)
                        return value;
                    retained[0]++;
                    return new VariableComputed(afterStamp, value.offset(), value.type(), lineStart, newEnd,
                        value.returnsRevision());
                });
                RESULTS.replaceAll((key, value) ->
                {
                    if (!key.startsWith(prefix) || !value.stamp().equals(beforeStamp))
                        return value;
                    retained[0]++;
                    return new Computed(afterStamp, value.type(), true, value.typesGeneration());
                });
            }
            Global.tempLog("assist-local-context", "after module=" + module + " stamp=" + afterStamp //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " preserve=" + preserve + " retained=" + retained[0]); //$NON-NLS-1$ //$NON-NLS-2$
            if (retained[0] > 0)
                publish();
        }
    }

    /**
     * Текущее состояние модуля: документа открытого редактора или файла ({@link ForeignModuleModel#stamp});
     * пустой — файла нет. В UI-потоке редакторы обходятся (не чаще {@link #STAMP_CHECK_TTL_NS});
     * вне UI (фоновый поток подсказки при наведении) — документ из {@link #KNOWN_DOCUMENTS}.
     */
    private static String currentStamp(URI module)
    {
        IFile file = ForeignModuleModel.fileOf(module);
        // Без файла — пустой штамп, как у результата activate для такой функции.
        if (file == null)
            return ""; //$NON-NLS-1$
        if (Display.getCurrent() == null)
        {
            java.lang.ref.WeakReference<IXtextDocument> known = KNOWN_DOCUMENTS.get(module);
            return ForeignModuleModel.stamp(file, known != null ? known.get() : null);
        }
        long now = System.nanoTime();
        StampCheck check = STAMP_CHECKS.get(module);
        if (check != null && now - check.checkedAt() < STAMP_CHECK_TTL_NS)
            return check.stamp();
        IXtextDocument document = ForeignModuleModel.openDocument(file);
        KNOWN_DOCUMENTS.put(module, new java.lang.ref.WeakReference<>(document));
        if (document != null)
            LOCAL_CONTEXT_LINES.computeIfAbsent(document, key -> new LocalContextLineCache(module, file, key));
        String stamp = ForeignModuleModel.stamp(file, document);
        STAMP_CHECKS.put(module, new StampCheck(stamp, now));
        return stamp;
    }

    /**
     * Выбор функции (текущая строка списка, наведение): чтение только её готового типа
     * встаёт в голову очереди. UI-поток.
     */
    static void activate(Target target)
    {
        requestFunction(target, true);
    }

    static void requestFunction(Target target, boolean first)
    {
        if (target == null)
            return;
        String key = target.key();
        if (first)
            priorityKey = key;
        IFile file = ForeignModuleModel.fileOf(target.module());
        if (file == null)
        {
            log("skip noFile key=" + key); //$NON-NLS-1$
            RESULTS.putIfAbsent(key, new Computed("", "")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        String stamp = currentStamp(target.module());
        var known = KNOWN_DOCUMENTS.get(target.module());
        IXtextDocument document = known != null ? known.get() : null;
        Computed ready = RESULTS.get(key);
        if (functionCurrent(ready, stamp, target.module()))
        {
            return;
        }
        // Открытый редактор уже содержит типы после расчёта запросов; индекс их ещё не хранит.
        if (document == null && requestIndex(target, stamp, first))
            return;
        if (document == null)
        {
            finishUnknown(new Request(target.module(), file, stamp, null, target.name(), null));
            return;
        }
        activateModel(target, file, document, stamp, first);
    }

    /**
     * Готовые типы по родной модели модуля, если он открыт в редакторе, — в голову очереди.
     * Неоткрытый модуль не загружается (своя модель задевала общий объект модуля). UI-поток.
     */
    private static void activateModel(Target target, IFile file, IXtextDocument document, String stamp,
        boolean first)
    {
        Computed ready = RESULTS.get(target.key());
        if (document == null || functionCurrent(ready, stamp, target.module()))
            return;
        enqueue(new Request(target.module(), file, stamp, document, target.name(), null), first);
    }

    /**
     * Экспортная функция закрытого модуля — тип из индекса сборки, как у штатной
     * подсказки EDT ({@link #computeIndex}); запрос — один на состояние модуля. Модуль с
     * несохранёнными правками — {@code false}: индекс устарел, нужен расчёт модели.
     * {@code first} — функция выбрана (в голову очереди). UI-поток.
     *
     * @return тип берётся (или уже взят) из индекса
     */
    private static boolean requestIndex(Target target, String stamp, boolean first)
    {
        if (target.exported() == null || stamp == null || stamp.isEmpty())
            return false;
        String key = target.key();
        // Пока сборка не закончилась, индекс может быть старым: тип с «?», после сборки — перечитать.
        boolean reliable = !buildPending();
        String requestStamp = stampOf(stamp, reliable);
        if (requestStamp.equals(INDEX_STAMPS.get(key)))
        {
            if (first)
            {
                synchronized (QUEUE)
                {
                    IndexRequest queued = null;
                    for (IndexRequest request : INDEX_QUEUE)
                    {
                        if (request.target().key().equals(key))
                            queued = request;
                    }
                    if (queued != null && INDEX_QUEUE.peekFirst() != queued)
                    {
                        INDEX_QUEUE.remove(queued);
                        INDEX_QUEUE.addFirst(queued);
                    }
                }
            }
            return true;
        }
        IFile file = ForeignModuleModel.fileOf(target.module());
        if (file == null || ForeignModuleModel.isDirty(file))
            return false;
        INDEX_STAMPS.put(key, requestStamp);
        synchronized (QUEUE)
        {
            if (first)
                INDEX_QUEUE.addFirst(new IndexRequest(target, file, stamp, reliable));
            else
                INDEX_QUEUE.addLast(new IndexRequest(target, file, stamp, reliable));
        }
        WORKER.schedule();
        return true;
    }

    /**
     * Индекс даёт предварительный тип. При расширенном расчёте закрытый модуль читается с диска
     * через {@link ForeignModuleModel}, с подготовкой результатов запросов запрошенного метода.
     * Открытый модуль читается только по родной модели редактора.
     */
    private static void computeIndex(IndexRequest request)
    {
        long start = System.nanoTime();
        Target target = request.target();
        String type = null;
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(target.module());
            com._1c.g5.v8.dt.bsl.typesystem.ExportMethodTypeProvider provider = rsp != null
                ? rsp.get(com._1c.g5.v8.dt.bsl.typesystem.ExportMethodTypeProvider.class) : null;
            List<TypeItem> types = provider != null ? provider.getMethodType(target.exported()) : null;
            if (types != null && !types.isEmpty())
            {
                // Собранный запрос своего модуля ресурса не имеет — ссылки разрешаются в живом
                // наборе ресурсов проекта (не в модели редактора).
                org.eclipse.emf.ecore.resource.ResourceSet set = null;
                Set<String> names = new LinkedHashSet<>();
                try
                {
                    for (TypeItem item : types)
                    {
                        TypeItem resolved = item;
                        if (item != null && item.eIsProxy())
                        {
                            if (target.exported().eResource() != null)
                                resolved = (TypeItem)EcoreUtil.resolve(item, target.exported());
                            else
                            {
                                if (set == null)
                                    set = BslOccurrenceContextResolver.newLiveResourceSet(rsp,
                                        request.file().getProject());
                                if (set != null)
                                    resolved = (TypeItem)EcoreUtil.resolve(item, set);
                            }
                        }
                        String name = typeName(resolved);
                        if (name != null && !name.isEmpty())
                            names.add(name);
                    }
                }
                finally
                {
                    if (set != null)
                        set.getResources().clear();
                }
                if (!names.isEmpty())
                    type = formatTypeNames(names);
            }
        }
        catch (Throwable error)
        {
            log("error index key=" + target.key() + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
        }
        log("index key=" + target.key() + " type=" + type //$NON-NLS-1$ //$NON-NLS-2$
            + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
        var knownDocument = KNOWN_DOCUMENTS.get(target.module());
        if ((knownDocument == null || knownDocument.get() == null)
            && BslDocCommentComputedTypes.isExtendedTypesEnabled(request.file().getProject()))
        {
            RESULTS.put(target.key(), new Computed(stampOf(request.stamp(), false), type != null ? type : ""));
            publish();
            String diskType = computeClosedFunction(request);
            if (diskType != null && !diskType.isEmpty())
                type = diskType;
        }
        if (!request.stamp().equals(ForeignModuleModel.stamp(request.file(), null)))
        {
            publish();
            return;
        }
        if (type != null)
        {
            // Пока читался индекс, редактор мог открыться и уже опубликовать готовый тип.
            var known = KNOWN_DOCUMENTS.get(target.module());
            if (known != null && known.get() != null)
            {
                publish();
                return;
            }
            Computed result = new Computed(stampOf(request.stamp(), request.reliable()), type);
            Computed old = RESULTS.put(target.key(), result);
            if (!result.equals(old))
                publish();
            if (!request.reliable())
                rereadAfterBuild(target);
            return;
        }
        // Индекс пуст — тип по модели модуля, если он открыт; иначе пересчёта не будет: итог
        // «без типа», без «?».
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() ->
            {
                IXtextDocument document = ForeignModuleModel.openDocument(request.file());
                String stamp = ForeignModuleModel.stamp(request.file(), document);
                if (!stamp.equals(request.stamp()))
                    return;
                if (document != null)
                    activateModel(target, request.file(), document, stamp, target.key().equals(priorityKey));
                else
                {
                    RESULTS.put(target.key(), new Computed(stamp, "")); //$NON-NLS-1$
                    publish();
                }
            });
    }

    /**
     * Принятый путь расчёта закрытого модуля с диска (как у типов внешних параметров).
     * Вызывается только WORKER под ForeignModuleModel.SERIAL. Наружу выходят имена и URI,
     * объекты временной модели не сохраняются; снимок открытого документа не создаётся.
     */
    private static String computeClosedFunction(IndexRequest request)
    {
        Target target = request.target();
        try
        {
            List<URI> semanticTypes = new java.util.ArrayList<>();
            ForeignModuleModel.Read<List<Object>> read = ForeignModuleModel.read(target.module(), request.file(),
                null, resource ->
                {
                    com._1c.g5.v8.dt.bsl.model.Module module = moduleOf(resource);
                    if (module == null)
                        return null;
                    for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
                    {
                        if (!(method instanceof com._1c.g5.v8.dt.bsl.model.Function)
                            || method.getName() == null || !method.getName().equalsIgnoreCase(target.name()))
                            continue;
                        var node = org.eclipse.xtext.nodemodel.util.NodeModelUtils.findActualNodeFor(method);
                        if (node == null || node.getLength() == 0)
                            return null;
                        BslStructureInsertCommentTypes.refreshQueryTypesForAssist(resource,
                            node.getOffset() + node.getLength() - 1,
                            status -> {});
                        List<?> ready = BslDocCommentComputedTypes.readyNormalReturnTypes(method);
                        if (ready == null || ready.isEmpty())
                            return null;
                        List<Object> parts = new java.util.ArrayList<>();
                        for (Object item : ready)
                        {
                            Object part = partOf(item);
                            if (part == null)
                                return null;
                            parts.add(part);
                        }
                        for (Object item : ready)
                        {
                            URI uri = item instanceof org.eclipse.emf.ecore.EObject object
                                ? EcoreUtil.getURI(object) : null;
                            if (uri == null || uri.isRelative())
                            {
                                semanticTypes.clear();
                                break;
                            }
                            semanticTypes.add(uri);
                        }
                        return parts;
                    }
                    return null;
                });
            List<Object> parts = read.value();
            if (parts == null)
                return null;
            Set<URI> uris = new LinkedHashSet<>();
            for (Object part : parts)
                if (part instanceof URI uri)
                    uris.add(uri);
            loadProxyNames(uris, target.module(), request.file().getProject());
            Set<String> names = new LinkedHashSet<>();
            for (Object part : parts)
            {
                String name = part instanceof URI uri ? PROXY_NAMES.get(uri) : (String)part;
                if (name == null || name.isEmpty())
                    return null;
                names.add(name);
            }
            String result = formatTypeNames(names);
            if (request.stamp().equals(ForeignModuleModel.stamp(request.file(), null)))
                rememberSemanticReturn(target, request.stamp(), semanticTypes);
            return result;
        }
        catch (Throwable error)
        {
            return null;
        }
    }

    /**
     * Тип из индекса прочитан во время сборки и показан с «?» — после сборки перечитать его без
     * нового показа строки: снять отметку запроса и перерисовать, перерисовка запросит заново.
     */
    private static void rereadAfterBuild(Target target)
    {
        Job waiter = new Job("Комфорт: тип функции после сборки") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    Job.getJobManager().join(org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
                    Job.getJobManager().join(org.eclipse.core.resources.ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
                }
                catch (InterruptedException | org.eclipse.core.runtime.OperationCanceledException e)
                {
                    return Status.CANCEL_STATUS;
                }
                INDEX_STAMPS.remove(target.key());
                log("reread after build key=" + target.key()); //$NON-NLS-1$
                publish();
                return Status.OK_STATUS;
            }
        };
        waiter.setSystem(true);
        waiter.schedule();
    }

    /** Один запрошенный элемент, без чтения типов остальных функций и переменных модуля. */
    private static void enqueue(Request request, boolean first)
    {
        if (request.nativeDocument() == null)
            return;
        int queued;
        synchronized (QUEUE)
        {
            Request pending = null;
            for (Request candidate : QUEUE)
                if (candidate.key().equals(request.key()))
                    pending = candidate;
            boolean sameState = request.state().equals(REQUEST_STAMPS.get(request.key()));
            if (sameState && pending == null)
                return;
            if (pending != null && sameState && (!first || QUEUE.peekFirst() == pending))
                return;
            if (pending != null)
                QUEUE.remove(pending);
            if (first)
                QUEUE.addFirst(request);
            else
                QUEUE.addLast(request);
            REQUEST_STAMPS.put(request.key(), request.state());
            queued = QUEUE.size();
        }
        log("schedule key=" + request.key() + " state=" + request.state() //$NON-NLS-1$ //$NON-NLS-2$
            + " first=" + first + " queued=" + queued); //$NON-NLS-1$ //$NON-NLS-2$
        WORKER.schedule();
    }

    /**
     * Модуль, открытый в редакторе: готовые типы его родной модели — только чтение. Отдельная
     * модель модуля не строится: её связывание и производное состояние задевали общий объект
     * модуля, и в других редакторах переставали разрешаться обращения через точку (#877, 23.10).
     * Чего готового нет — остаётся предварительным.
     */
    private static void compute(Request request)
    {
        if (!requestStillCurrent(request) || hasCompletedResult(request))
        {
            log("native.skip key=" + request.key() + " state=" + request.state()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        long start = System.nanoTime();
        Set<String> nativeDone = ConcurrentHashMap.newKeySet();
        if (request.nativeDocument() != null)
        {
            Integer missing = null;
            List<Pending> pending = new java.util.ArrayList<>();
            // #region temp timing #877: ожидание блокировки документа против работы под ней
            long[] entered = new long[1];
            try
            {
                missing = request.nativeDocument().readOnly(resource ->
                {
                    entered[0] = System.nanoTime();
                    String actualStamp = ForeignModuleModel.stamp(request.file(), request.nativeDocument());
                    log("native.request key=" + request.key() + " stamp=" + actualStamp //$NON-NLS-1$ //$NON-NLS-2$
                        + " document=" + System.identityHashCode(request.nativeDocument()) //$NON-NLS-1$
                        + " model=" + System.identityHashCode(resource)); //$NON-NLS-1$
                    if (!request.stamp().equals(actualStamp))
                        return null;
                    return readyFromNative(resource, request, nativeDone, pending);
                });
            }
            catch (Throwable error)
            {
                log("error native module=" + request.module() + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
            }
            long end = System.nanoTime();
            log("native module=" + request.module() + " missing=" + missing //$NON-NLS-1$ //$NON-NLS-2$
                + " ms=" + (end - start) / 1_000_000L //$NON-NLS-1$
                + " waitMs=" + (entered[0] > 0 ? (entered[0] - start) / 1_000_000L : -1) //$NON-NLS-1$
                + " underLockMs=" + (entered[0] > 0 ? (end - entered[0]) / 1_000_000L : -1)); //$NON-NLS-1$
            // #endregion
            if (!requestStillCurrent(request))
                return;
            resolvePending(request, pending);
            if (!requestStillCurrent(request))
                return;
            // Не найден элемент, модель недоступна или чтение завершилось ошибкой: запрос
            // окончен. Не оставлять бесконечное ожидание и не выдавать старый тип за итоговый.
            if (!hasCompletedResult(request))
                finishUnknown(request);
        }
    }

    private static void finishUnknown(Request request)
    {
        if (!requestStillCurrent(request))
            return;
        if (request.variable() != null)
            storeNative(request, new VariableComputed(request.stamp(), request.variable().offset(), "")); //$NON-NLS-1$
        else
            storeNative(request, new Computed(request.stamp(), "")); //$NON-NLS-1$
        log("finished unknown key=" + request.key() + " state=" + request.state()); //$NON-NLS-1$ //$NON-NLS-2$
        publish();
    }

    private static boolean requestStillCurrent(Request request)
    {
        return request.contextRevision() == contextRevision(request.module())
            && (request.variable() == null || request.returnsRevision() == semanticRevision())
            && (request.variable() != null
                || request.typesGeneration() == BslDocCommentComputedTypes.typesGeneration())
            && request.stamp().equals(ForeignModuleModel.stamp(request.file(), request.nativeDocument()));
    }

    private static boolean hasCompletedResult(Request request)
    {
        if (request.variable() != null)
        {
            VariableComputed computed = VARIABLE_RESULTS.get(request.key());
            return computed != null && computed.matches(request.stamp(), request.variable().offset());
        }
        Computed computed = RESULTS.get(request.key());
        return computed != null && computed.stamp().equals(request.stamp())
            && computed.typesGeneration() == request.typesGeneration();
    }

    /** Старый фоновый запрос не перезаписывает результат, сохранённый правкой текущей строки. */
    private static boolean storeNative(Request request, Computed result)
    {
        Computed nativeResult = new Computed(result.stamp(), result.type(), result.linePreserved(),
            request.nativeDocument() != null ? request.typesGeneration() : -1);
        boolean[] changed = { false };
        RESULTS.compute(request.key(), (key, old) ->
        {
            if (!requestStillCurrent(request))
                return old;
            changed[0] = !nativeResult.equals(old);
            return nativeResult;
        });
        return changed[0];
    }

    private static boolean storeNative(Request request, VariableComputed result)
    {
        VariableComputed nativeResult = new VariableComputed(result.stamp(), result.offset(), result.type(),
            result.lineStart(), result.lineEnd(), request.returnsRevision());
        boolean[] changed = { false };
        VARIABLE_RESULTS.compute(request.key(), (key, old) ->
        {
            if (!requestStillCurrent(request))
                return old;
            changed[0] = !nativeResult.equals(old);
            return nativeResult;
        });
        return changed[0];
    }

    /**
     * Готовая часть типа: имя, адрес ещё не загруженной ссылки ({@link URI}; уже известное имя —
     * из {@link #PROXY_NAMES}) или {@code null} — не тип. Только чтение.
     */
    private static Object partOf(Object item)
    {
        if (!(item instanceof TypeItem type))
            return null;
        if (type.eIsProxy())
        {
            URI uri = ((InternalEObject)type).eProxyURI();
            if (uri == null)
                return null;
            String known = PROXY_NAMES.get(uri);
            return known != null ? known : uri;
        }
        String name = typeName(type);
        return name != null && !name.isEmpty() ? name : null;
    }

    /**
     * Загрузка ссылок на типы готовых типов, прочитанных под блокировкой ({@link Pending}), — вне
     * блокировки документа, в своём наборе ресурсов проекта (как у индекса, {@link #computeIndex}):
     * модель редактора не меняется. Итоги пишутся по штампу прочитанного состояния.
     */
    /**
     * Имена типов по адресам ссылок — в {@link #PROXY_NAMES}; загрузка в своём наборе ресурсов
     * проекта (модель редактора не меняется). Не UI-поток, вне блокировок документов.
     */
    private static void loadProxyNames(Set<URI> uris, URI module,
        org.eclipse.core.resources.IProject project)
    {
        uris.removeIf(PROXY_NAMES::containsKey);
        if (uris.isEmpty())
            return;
        org.eclipse.emf.ecore.resource.ResourceSet set = null;
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(module);
            set = rsp != null ? BslOccurrenceContextResolver.newLiveResourceSet(rsp, project) : null;
            for (URI uri : uris)
            {
                String name = null;
                try
                {
                    org.eclipse.emf.ecore.EObject object = set != null ? set.getEObject(uri, true) : null;
                    name = object instanceof TypeItem type ? typeName(type) : null;
                }
                catch (RuntimeException error)
                {
                    log("error proxy uri=" + uri + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (name == null || name.isEmpty())
                    log("proxy unresolved uri=" + uri); //$NON-NLS-1$
                PROXY_NAMES.put(uri, name != null ? name : ""); //$NON-NLS-1$
            }
        }
        finally
        {
            if (set != null)
                set.getResources().clear();
        }
    }

    private static void resolvePending(Request request, List<Pending> pending)
    {
        if (pending.isEmpty())
            return;
        long start = System.nanoTime();
        Set<URI> uris = new LinkedHashSet<>();
        for (Pending item : pending)
        {
            for (Object part : item.parts())
            {
                if (part instanceof URI uri && !PROXY_NAMES.containsKey(uri))
                    uris.add(uri);
            }
        }
        loadProxyNames(uris, request.module(), request.file().getProject());
        for (Pending item : pending)
        {
            Set<String> names = new LinkedHashSet<>();
            for (Object part : item.parts())
            {
                String name = part instanceof URI uri ? PROXY_NAMES.get(uri) : (String)part;
                if (name == null || name.isEmpty())
                {
                    names.clear();
                    break;
                }
                names.add(name);
            }
            // Не загрузилась — пересчёта больше не будет: итог «без типа», без «?».
            String type = formatTypeNames(names);
            if (item.offset() >= 0)
                storeNative(request, new VariableComputed(item.stamp(), item.offset(), type));
            else
                storeNative(request, new Computed(item.stamp(), type));
            log("  resolved key=" + item.key() + " stamp=" + item.stamp() + " type=" + type); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        log("proxies module=" + request.module() + " items=" + pending.size() + " loaded=" + uris.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
        publish();
    }

    /**
     * Готовый тип одной запрошенной функции родной модели редактора — только чтение рассчитанных
     * EDT состояний ({@link BslDocCommentComputedTypes#readyNormalReturnTypes}): ни
     * {@code TypesComputer}, ни разрешения прокси под блокировкой его документа. Функция без
     * готового состояния получает пустой итог (показ штатный); ссылки на типы загружаются после
     * блокировки ({@link #resolvePending}).
     *
     * @return число функций без готового типа; {@code null} — модели нет
     */
    private static Integer readyFromNative(Resource resource, Request request, Set<String> done,
        List<Pending> pending)
    {
        com._1c.g5.v8.dt.bsl.model.Module module = moduleOf(resource);
        if (module == null)
            return null;
        // Штамп — того состояния, которое читается сейчас, под блокировкой.
        String stamp = ForeignModuleModel.stamp(request.file(), request.nativeDocument());
        if (request.variable() != null)
            return computeVariable(module, request, stamp, done, pending);
        int missing = 0;
        boolean dirty = false;
        int noState = 0;
        int emptyState = 0;
        int proxyType = 0;
        String proxyExample = null;
        List<String> missingExamples = new java.util.ArrayList<>();
        for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
        {
            if (method.getName() == null || !method.getName().equalsIgnoreCase(request.method())
                || !(method instanceof com._1c.g5.v8.dt.bsl.model.Function))
                continue;
            List<?> ready = BslDocCommentComputedTypes.readyNormalReturnTypes(method);
            List<Object> parts = new java.util.ArrayList<>();
            boolean unloaded = false;
            boolean complete = ready != null && !ready.isEmpty();
            // #region temp #877: почему у функции нет готового типа
            if (ready == null)
                noState++;
            else if (ready.isEmpty())
                emptyState++;
            // #endregion
            if (complete)
            {
                for (Object item : ready)
                {
                    Object part = partOf(item);
                    if (part == null)
                    {
                        complete = false;
                        break;
                    }
                    unloaded |= part instanceof URI;
                    parts.add(part);
                }
            }
            String key = request.module() + "#" + method.getName().toLowerCase(Locale.ROOT); //$NON-NLS-1$
            if (complete && unloaded)
            {
                // Ссылки на типы загружаются после блокировки (resolvePending); до того — «?».
                proxyType++;
                if (proxyExample == null)
                    proxyExample = method.getName() + ":" + parts; //$NON-NLS-1$
                pending.add(new Pending(key, parts, stamp, -1));
                done.add(key);
                continue;
            }
            if (!complete)
            {
                missing++;
                if (missingExamples.size() < 5)
                    missingExamples.add(method.getName());
                // Готового типа нет, и пересчёта не будет: итог пустой — показ штатный, без «?».
                // Тип из «Возвращаемое значение» (чтение комментария) — только предварительный
                // при следующем пересчёте.
                String comment;
                try
                {
                    comment = commentType(BslStructureInsertCommentTypes.parseMethodComment(method, module));
                }
                catch (RuntimeException error)
                {
                    comment = ""; //$NON-NLS-1$
                }
                COMMENT_TYPES.put(key, new Computed(stamp, comment));
                dirty |= storeNative(request, new Computed(stamp, "")); //$NON-NLS-1$
                continue;
            }
            List<String> names = new java.util.ArrayList<>();
            for (Object part : parts)
                names.add((String)part);
            String type = formatTypeNames(names);
            List<URI> semanticTypes = new java.util.ArrayList<>();
            for (Object item : ready)
            {
                URI uri = item instanceof org.eclipse.emf.ecore.EObject object ? EcoreUtil.getURI(object) : null;
                if (uri == null || uri.isRelative())
                {
                    semanticTypes.clear();
                    break;
                }
                semanticTypes.add(uri);
            }
            rememberSemanticReturn(new Target(request.module(), request.method(), null), stamp, semanticTypes);
            Computed result = new Computed(stamp, type);
            done.add(key);
            dirty |= storeNative(request, result);
        }
        log("native reasons module=" + request.module() + " noState=" + noState + " emptyState=" + emptyState //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " proxyType=" + proxyType + " proxyExample=" + proxyExample + " examples=" + missingExamples); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (dirty)
            publish();
        return Integer.valueOf(missing);
    }

    /**
     * Тип одной запрошенной переменной в точке списка по родной
     * модели редактора — только готовое состояние NORMAL в точке
     * ({@code getTypeStateProvider().get(NORMAL).getNearestByOffset} — так строку переменной строит
     * сама EDT), без досчёта и разрешения прокси. Публикуются сразу.
     *
     * @return число переменных без готового типа
     */
    private static int computeVariable(com._1c.g5.v8.dt.bsl.model.Module module, Request request,
        String stamp, Set<String> done, List<Pending> pending)
    {
        VariableTarget target = request.variable();
        if (target == null)
            return 0;
        int offset = target.offset();
        BslStructureInsertCommentTypes.refreshExternalFunctionTypes(module.eResource(), offset);
        long start = System.nanoTime();
        org.eclipse.emf.ecore.EObject object = module.eResource().getEObject(target.fragment());
        if (!(object instanceof com._1c.g5.v8.dt.bsl.model.Variable variable)
            || variable.getName() == null || !variable.getName().equalsIgnoreCase(target.name()))
        {
            storeNative(request, new VariableComputed(stamp, offset, "")); //$NON-NLS-1$
            log("variable unavailable key=" + target.key() + " fragment=" + target.fragment()); //$NON-NLS-1$ //$NON-NLS-2$
            publish();
            return 1;
        }
        com._1c.g5.v8.dt.bsl.model.Method method = org.eclipse.xtext.EcoreUtil2.getContainerOfType(
            variable, com._1c.g5.v8.dt.bsl.model.Method.class);
        BslStructureInsertCommentTypes.requestVariableReturnSources(module.eResource(), variable, method, offset,
            target.key());
        com._1c.g5.v8.dt.mcore.util.Environments environments = method != null ? method.environments()
            : module.environments();
        String key = target.key();
        List<Object> parts = environments != null ? readyVariableType(variable, offset, environments) : null;
        if (parts != null && parts.stream().anyMatch(URI.class::isInstance))
        {
            pending.add(new Pending(key, parts, stamp, offset));
            done.add(key);
            log("  var key=" + key + " stamp=" + stamp + " pending=" + parts); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return 0;
        }
        List<String> names = new java.util.ArrayList<>();
        if (parts != null)
            for (Object part : parts)
                names.add((String)part);
        String type = formatTypeNames(names);
        done.add(key);
        VariableComputed result = new VariableComputed(stamp, offset, type);
        boolean changed = storeNative(request, result);
        log("variable key=" + key + " offset=" + offset + " stamp=" + stamp + " type=" + type //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
        // Даже при том же типе новый штамп завершает ожидание: строка должна снять «?».
        if (changed)
            publish();
        return parts == null ? 1 : 0;
    }

    /**
     * Готовый тип переменной в точке — тот же вызов, что у боковой подсказки EDT
     * ({@code BslDocumentationProvider.getDocByNotFormalParamVariable}):
     * {@code TypeSystemUtil.getVariableTypesAtOffset} — ближайшее состояние для сред метода и его
     * подсостояния этих сред. При включённом расширенном расчёте тип дополняется элементами массива
     * через общий {@link BslStructureInsertCommentTypes#arrayVariableTypes}.
     * Части типа — {@link #partOf} (имена и адреса не загруженных ссылок); {@code null} — готового нет.
     */
    private static List<Object> readyVariableType(com._1c.g5.v8.dt.bsl.model.Variable variable, int offset,
        com._1c.g5.v8.dt.mcore.util.Environments environments)
    {
        try
        {
            List<TypeItem> types = com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil.getVariableTypesAtOffset(
                variable, offset, environments);
            // TypeSystemUtil может загрузиться до раннего хука; строка использует тот же расчёт явно.
            types = BslStructureInsertCommentTypes.arrayVariableTypes(types, variable, offset, environments);
            if (types == null || types.isEmpty())
            {
                // #region agent log — причина пустого типа переменной (временно)
                log("variable miss name=" + variable.getName() + " class=" + variable.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + " offset=" + offset + " env=" + environments); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return null;
            }
            List<Object> parts = new java.util.ArrayList<>();
            for (TypeItem item : types)
            {
                Object part = partOf(item);
                if (part == null)
                {
                    log("variable miss name=" + variable.getName() + " reason=" //$NON-NLS-1$ //$NON-NLS-2$
                        + (item == null ? "nullItem" : "noName " + item.eClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
                    return null;
                }
                parts.add(part);
            }
            return parts;
        }
        catch (RuntimeException error)
        {
            log("variable miss name=" + variable.getName() + " error=" + error); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /** Общий заголовок рассчитанного типа для боковой подсказки и подсказки при наведении. */
    static Object withFunctionReturnType(Object info, FunctionReturnTypes.Target target, ITextViewer viewer)
    {
        if (info == null || target == null || viewer == null)
            return info;
        boolean browserInput = IrBslHoverHtml.isBslBrowserInput(info);
        String html = browserInput ? IrBslHoverHtml.readDisplayHtml(info)
            : info instanceof String text ? text : null;
        if (html == null)
            return info;
        // При асинхронной смене строки браузер ещё может содержать описание предыдущей функции.
        int title = html.indexOf("<span class=\"title\">");
        int titleEnd = title >= 0 ? html.indexOf("</span>", title) : -1;
        if (titleEnd < 0 || !html.substring(title, titleEnd).replaceAll("<[^>]*>", "")
            .toLowerCase(java.util.Locale.ROOT).startsWith(target.name().toLowerCase(java.util.Locale.ROOT) + "("))
            return info;
        BslXtextEditor editor = SmartContentAssistProcessor.resolveEditorForLiteralAudit(viewer);
        State current = state(target);
        String result = injectFunctionTypeHeading(html, current, viewer, editor);
        if (browserInput)
            FUNCTION_HINTS.put(info, new FunctionHint(target, new java.lang.ref.WeakReference<>(viewer), current));
        return result.equals(html) ? info : browserInput ? IrBslHoverHtml.withHtml(info, result) : result;
    }

    /** Повторное оформление только при смене типа; вызывается из setInput и публикации расчёта. */
    static boolean refreshBoundFunctionType(Object input)
    {
        FunctionHint hint = FUNCTION_HINTS.get(input);
        if (hint == null)
            return false;
        ITextViewer viewer = hint.viewer().get();
        if (viewer == null || java.util.Objects.equals(hint.shown(), state(hint.target())))
            return false;
        String before = IrBslHoverHtml.readDisplayHtml(input);
        withFunctionReturnType(input, hint.target(), viewer);
        boolean changed = !java.util.Objects.equals(before, IrBslHoverHtml.readDisplayHtml(input));
        return changed;
    }

    private static String injectFunctionTypeHeading(String html, FunctionReturnTypes.State state,
        ITextViewer viewer, BslXtextEditor editor)
    {
        // Пересчёта нет и итога нет — штатный заголовок без «?».
        if (state == null)
            return html;
        int heading = html.indexOf("infocontrol-heading"); //$NON-NLS-1$
        int contentStart = heading >= 0 ? html.indexOf('>', heading) + 1 : 0;
        int headingEnd = contentStart > 0 ? html.indexOf("</div>", contentStart) : -1; //$NON-NLS-1$
        int title = contentStart > 0 ? html.indexOf("<span class=\"title\">", contentStart) : -1; //$NON-NLS-1$
        if (headingEnd < 0 || title < 0 || title > headingEnd)
        {
            return html;
        }
        String segment = html.substring(contentStart, title);
        String keyword = "Функция "; //$NON-NLS-1$
        int keywordAt = segment.indexOf(keyword);
        if (keywordAt < 0)
        {
            keyword = "Function "; //$NON-NLS-1$
            keywordAt = segment.indexOf(keyword);
        }
        if (keywordAt < 0)
        {
            return html;
        }
        int typeStart = contentStart + keywordAt + keyword.length();
        String type;
        if (state.fresh())
        {
            if (state.type().isEmpty())
                return html;
            type = "&lt;" + linkedFunctionTypes(unwrapAngles(state.type()), viewer, editor) + "&gt;";
        }
        else
        {
            String inner;
            if (!state.type().isEmpty())
                inner = linkedFunctionTypes(unwrapAngles(state.type()), viewer, editor);
            else
            {
                // Штатное «&lt;<a …>Строка</a>&gt;*»: внутренность с её ссылками, без скобок.
                String edtType = html.substring(typeStart, title).trim();
                int close = edtType.lastIndexOf("&gt;"); //$NON-NLS-1$
                inner = edtType.startsWith("&lt;") && close > 4 //$NON-NLS-1$
                    ? edtType.substring(4, close).trim() : edtType;
            }
            type = inner.isEmpty()
                ? "&lt;" + FunctionReturnTypes.UNKNOWN + "&gt;" //$NON-NLS-1$ //$NON-NLS-2$
                : "&lt;" + FunctionReturnTypes.UNKNOWN + " | " + inner + "&gt;"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return MetadataTypeMenu.style(html.substring(0, typeStart) + type + " " + html.substring(title)); //$NON-NLS-1$
    }

    /** Ссылки и меню типов создаются тем же механизмом, что в остальных заголовках EDT. */
    private static String linkedFunctionTypes(String names, ITextViewer viewer, BslXtextEditor editor)
    {
        List<String> parts = new java.util.ArrayList<>();
        for (String part : names.split("\\|")) //$NON-NLS-1$
        {
            String name = part.trim();
            var type = MetadataTypeMenu.resolveType(viewer, name);
            parts.add(type != null ? MetadataTypeMenu.link(type, name, editor)
                : HTMLPrinter.convertToHTMLContent(name));
        }
        return String.join(" | ", parts); //$NON-NLS-1$
    }

    private static String unwrapAngles(String type)
    {
        return type.length() >= 2 && type.charAt(0) == '<' && type.charAt(type.length() - 1) == '>'
            ? type.substring(1, type.length() - 1).trim() : type;
    }

    /** Тип модели привязан также к поколению расчёта, индекс — только к закрытому модулю. */
    private static boolean functionCurrent(Computed result, String stamp, URI module)
    {
        if (result == null || !result.stamp().equals(stamp))
            return false;
        var known = KNOWN_DOCUMENTS.get(module);
        if (known != null && known.get() != null)
            return result.typesGeneration() == BslDocCommentComputedTypes.typesGeneration();
        return result.typesGeneration() < 0;
    }

    /** Готовые типы сменились без правки текста: повторно прочитать видимые функции. */
    static void computedTypesChanged()
    {
        if (!RESULTS.isEmpty())
        {
            publish();
        }
    }

    private static void publish()
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(FunctionReturnTypes::refresh);
    }

    private static void refresh()
    {
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        boolean visible = assistant != null && ContentAssistPopupSync.isPopupVisible(assistant);
        log("refresh list=" + visible + " listeners=" + LISTENERS.size()); //$NON-NLS-1$ //$NON-NLS-2$
        if (visible)
        {
            ContentAssistPopupSync.refreshProposalTable(assistant);
            var table = ContentAssistPopupSync.getProposalTable(ContentAssistPopupSync.getPopupObject(assistant));
            if (table != null && !table.isDisposed()
                && table.getData(TypeDemandSession.KEY) instanceof TypeDemandSession session)
                session.traceVisibleRows("refresh"); //$NON-NLS-1$
        }
        if (NATIVE_HINTS_CHANGED.getAndSet(false) && visible)
        {
            List<org.eclipse.jface.text.contentassist.ICompletionProposal> rows =
                ContentAssistPopupSync.visibleProposals(assistant);
            var table = ContentAssistPopupSync.getProposalTable(ContentAssistPopupSync.getPopupObject(assistant));
            int selected = table != null && !table.isDisposed() ? table.getSelectionIndex() : -1;
            if (rows != null && selected >= 0 && selected < rows.size()
                && rows.get(selected) instanceof SmartCompletionProposal proposal)
                proposal.refreshNativeVariableSideHint(assistant);
        }
        IrBslHoverHtml.refreshComputedFunctionTypes();
        LISTENERS.removeIf(listener -> !listener.getAsBoolean());
    }

    private static com._1c.g5.v8.dt.bsl.model.Module moduleOf(Resource resource)
    {
        return resource instanceof XtextResource && !resource.getContents().isEmpty()
            && resource.getContents().get(0) instanceof com._1c.g5.v8.dt.bsl.model.Module module
            ? module : null;
    }

    /** Только ключи сортировки и имена: объекты модели редактора здесь не удерживаются. */
    private static final Map<String, TypeDescriptionPresentationHook.TypeOrder> TYPE_ORDERS =
        new ConcurrentHashMap<>();

    /** Штатный сокращённый перечень: общий порядок типов, многоточие остаётся в конце. */
    static String sortProposalTypeDisplay(String display, Object info)
    {
        if (display == null)
            return null;
        int owner = display.indexOf(" ~ "); //$NON-NLS-1$
        int end = owner >= 0 ? owner : display.length();
        int colon = display.lastIndexOf(" : ", end); //$NON-NLS-1$
        if (colon < 0 || display.lastIndexOf(')', end) > colon)
            return display;
        String text = display.substring(colon + 3, end).trim();
        if (!text.startsWith("<") || !text.endsWith(">")) //$NON-NLS-1$ //$NON-NLS-2$
            return display;
        rememberProposalTypeOrders(info, 0);
        List<String> names = new java.util.ArrayList<>();
        boolean unknown = false;
        boolean more = false;
        // Запятые после «из» принадлежат элементам массива, а не внешнему набору.
        String separator = text.contains(" из ") || text.indexOf('|') >= 0 ? "\\|" : ","; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String part : text.substring(1, text.length() - 1).split(separator))
        {
            String name = part.trim();
            if (UNKNOWN.equals(name))
                unknown = true;
            else if ("...".equals(name)) //$NON-NLS-1$
                more = true;
            else
                names.add(name);
        }
        String sorted = formatTypeNames(names);
        String inner = sorted.isEmpty() ? "" : sorted.substring(1, sorted.length() - 1); //$NON-NLS-1$
        if (unknown)
            inner = UNKNOWN + (inner.isEmpty() ? "" : " | " + inner); //$NON-NLS-1$ //$NON-NLS-2$
        if (more)
            inner += (inner.isEmpty() ? "" : " | ") + "..."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String result = display.substring(0, colon + 3) + "<" + inner + ">" + display.substring(end); //$NON-NLS-1$ //$NON-NLS-2$
        return result.equals(display) ? display : result;
    }

    /** Готовые типы строки; прокси списка предложений не разрешаются. */
    private static void rememberProposalTypeOrders(Object info, int depth)
    {
        if (depth > 3 || !(info instanceof InternalEObject object) || object.eIsProxy())
            return;
        if (info instanceof ProposalElement)
        {
            Object value = object.eGet(BslPackage.PROPOSAL_ELEMENT__LIST, false, false);
            if (value instanceof InternalEList<?> list)
            {
                var entries = list.basicIterator();
                while (entries.hasNext())
                    rememberProposalTypeOrders(entries.next(), depth + 1);
            }
        }
        else if (info instanceof ParamSet)
            rememberProposalTypeOrders(object.eInternalContainer(), depth + 1);
        else if (info instanceof com._1c.g5.v8.dt.mcore.Property property)
            property.getTypes().forEach(FunctionReturnTypes::typeName);
        else if (info instanceof com._1c.g5.v8.dt.mcore.Method method)
            method.getRetValType().forEach(FunctionReturnTypes::typeName);
    }

    /** Тот же порядок, что в подсказках; при неизвестном ключе сохраняется исходный порядок. */
    private static String formatTypeNames(java.util.Collection<String> names)
    {
        List<String> sorted = new java.util.ArrayList<>(new LinkedHashSet<>(names));
        Map<String, TypeDescriptionPresentationHook.TypeOrder> orders = new java.util.HashMap<>();
        for (String name : sorted)
        {
            var order = TYPE_ORDERS.get(name);
            if (order == null)
                break;
            orders.put(name, order);
        }
        if (orders.size() == sorted.size())
            sorted.sort((left, right) -> orders.get(left).compareTo(orders.get(right)));
        return sorted.isEmpty() ? "" : "<" + String.join(" | ", sorted) + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Текст того же набора типов, который EDT показывает ссылками в боковой подсказке. */
    static String typeSetText(List<TypeItem> types)
    {
        return formatTypeNames(types.stream().map(FunctionReturnTypes::typeName)
            .filter(name -> name != null && !name.isEmpty()).toList());
    }

    static String typeName(TypeItem type)
    {
        return typeName(type, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static String typeName(TypeItem type, Set<TypeItem> visiting)
    {
        if (type == null)
            return null;
        // McoreUtil читает имя прокси через штатный ITypeNameResolver, не загружая сам тип.
        String russian = com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeNameRu(type);
        String english = com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeName(type);
        String name = SmartContentAssistProcessor.localizeAssistTypeSegment(
            russian != null && !russian.isEmpty() ? russian : english);
        if (name != null && !name.isEmpty() && !type.eIsProxy())
        {
            var order = TypeDescriptionPresentationHook.assistOrder(type);
            if (order != null)
                TYPE_ORDERS.put(name, order);
        }
        if (name == null || !(type instanceof com._1c.g5.v8.dt.mcore.Type collection)
            || !(type instanceof com._1c.g5.v8.dt.bsl.model.ExtendedCollectionType
                || "Array".equals(type.getName())) //$NON-NLS-1$
            || collection.getCollectionElementTypes() == null || !visiting.add(type))
            return name;
        try
        {
            Set<String> elements = new LinkedHashSet<>();
            for (TypeItem element : collection.getCollectionElementTypes().allTypes())
            {
                // Неизвестный элемент сохраняет обобщённое имя коллекции, как в подсказках.
                if (element == null || "Arbitrary".equals(com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeName(element))) //$NON-NLS-1$
                    return name;
                String elementName = typeName(element, visiting);
                if (elementName == null || elementName.isEmpty())
                    return name;
                elements.add(elementName);
            }
            String result = elements.isEmpty() ? name : name + " из " + String.join(", ", elements); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
        finally
        {
            visiting.remove(type);
        }
    }

    /** Временный лог (снять после подтверждения). */
    private static void log(String text)
    {
        Global.tempLog(LOG, text);
    }
}
