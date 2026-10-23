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
import org.eclipse.swt.widgets.Display;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.model.BslContextDefMethod;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.ProposalElement;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.TypeItem;

/**
 * Тип возврата функции конфигурации (issue #877) для строки списка автодополнения
 * ({@link SmartCompletionProposal}) и заголовка подсказки при наведении ({@link BslEditorHoverHook}).
 * EDT пишет его только из комментария «Возвращаемое значение» и не досчитывает — это тип
 * предварительный, показывается с {@code ?} первым элементом ({@code <?, Строка>}), без него —
 * {@code <?>}.
 *
 * <p>При выборе строки или наведении в фоне строится модель модуля функции
 * ({@link ForeignModuleModel}) — одна на модуль: по ней считаются типы всех его функций, первой —
 * выбранной; рассчитанный тип заменяет предварительный, и показ перерисовывается
 * ({@link #addListener}). Модуль редактора, в котором открыт список, считается сразу при показе
 * списка ({@link #prefetchEditorModule}). Тип итоговый, только пока модуль в том состоянии, по
 * которому он посчитан ({@link #state}). Модуль, открытый в редакторе (в т.ч. активном —
 * согласовано с автором), даёт из родной модели только готовые типы — то же, что читает боковая
 * подсказка EDT, без досчёта ({@link #readyFromNative}); чего готового нет — отдельной моделью из
 * снимка текста (её расчёт ввод не блокирует).
 *
 * <p>Тот же расчёт модуля даёт и типы переменных строк списка — в точке, где открыт список
 * ({@link #variableState}): EDT в строке переменной тип не пишет.
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

    /** Тип ({@code <А, Б>}, пустой — не определён) и штамп модуля, по которому посчитан. */
    private record Computed(String stamp, String type)
    {
    }

    /**
     * Запрос расчёта всех функций модуля в состоянии на момент запроса (снимок текста и штамп).
     * {@code nativeDocument} — модуль открыт в редакторе: готовые типы берутся из его родной
     * модели ({@link #readyFromNative}), остальные — отдельной моделью.
     */
    private record Request(URI module, IFile file, String text, String stamp, IXtextDocument nativeDocument)
    {
    }

    /** Перерисовка показа по ходу расчёта модуля — не чаще. */
    private static final long REFRESH_INTERVAL_NS = 300_000_000L;

    private static final Map<String, Computed> RESULTS = new ConcurrentHashMap<>();

    /**
     * Переменная (параметр) метода {@code method} (нижний регистр; пустой — переменная модуля)
     * модуля {@code module}, строка которой показана для точки {@code offset} (модельное смещение
     * точки, в которой открыт список).
     */
    record VariableTarget(URI module, String method, String name, int offset)
    {
        String key()
        {
            return module + "#" + method + "#var#" + name.toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Тип переменной в точке {@code offset} по состоянию модуля {@code stamp}. */
    private record VariableComputed(String stamp, int offset, String type)
    {
    }

    private static final Map<String, VariableComputed> VARIABLE_RESULTS = new ConcurrentHashMap<>();
    /**
     * Модуль → точка ({@code метод:смещение}) последней показанной строки переменной: её
     * переменные считаются в расчёте модуля первыми. Точка входит в состояние модуля
     * ({@link #stateOf}): другая точка того же текста — новый расчёт.
     */
    private static final Map<URI, String> POINTS = new ConcurrentHashMap<>();
    /**
     * Предварительные типы — из «Возвращаемое значение» комментария, по разбору модуля без
     * связывания ({@link #computeComments}): EDT в строке тип часто не пишет вовсе.
     */
    private static final Map<String, Computed> COMMENT_TYPES = new ConcurrentHashMap<>();
    /** Модуль → штамп, для которого его расчёт ждёт в очереди, идёт или уже сделан. */
    private static final Map<URI, String> MODULE_STAMPS = new ConcurrentHashMap<>();
    /** То же для разбора комментариев модуля. */
    private static final Map<URI, String> COMMENT_STAMPS = new ConcurrentHashMap<>();
    /** Модули, ожидающие разбора комментариев; разбор быстрый и идёт раньше полных расчётов. */
    private static final java.util.Deque<Request> COMMENT_QUEUE = new java.util.ArrayDeque<>();

    /** Запрос типа экспортной функции из индекса сборки для состояния модуля {@code stamp}. */
    private record IndexRequest(Target target, IFile file, String stamp)
    {
    }

    /** Экспортные функции, ожидающие чтения индекса; самая быстрая очередь, текущая — в голове. */
    private static final java.util.Deque<IndexRequest> INDEX_QUEUE = new java.util.ArrayDeque<>();
    /** Функция → штамп модуля, для которого индекс уже запрошен. */
    private static final Map<String, String> INDEX_STAMPS = new ConcurrentHashMap<>();
    /**
     * Модуль → имя выбранной функции (нижний регистр): в расчёте модуля она считается первой,
     * в том числе если её выбрали, пока модуль уже считается.
     */
    private static final Map<URI, String> PRIORITY = new ConcurrentHashMap<>();
    /**
     * Модули, ожидающие расчёта; голова — модуль функции, выбранной последней: он считается
     * следующим, вне очереди. Под монитором самой очереди.
     */
    private static final java.util.Deque<Request> QUEUE = new java.util.ArrayDeque<>();
    /** Перерисовка показов после новых типов; {@code false} — слушатель больше не нужен. UI-поток. */
    private static final List<BooleanSupplier> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Один модуль за запуск: правило {@link ForeignModuleModel#SERIAL} отпускается между
     * расчётами, и следующим берётся модуль выбранной функции.
     */
    private static final Job WORKER = new Job("Комфорт: тип функции") //$NON-NLS-1$
    {
        @Override
        protected IStatus run(IProgressMonitor monitor)
        {
            IndexRequest index;
            Request request = null;
            boolean comments = false;
            synchronized (QUEUE)
            {
                index = INDEX_QUEUE.pollFirst();
                if (index == null)
                {
                    request = COMMENT_QUEUE.pollFirst();
                    comments = request != null;
                    if (request == null)
                        request = QUEUE.pollFirst();
                }
            }
            try
            {
                if (index != null)
                    computeIndex(index);
                else if (comments)
                    computeComments(request);
                else if (request != null)
                    compute(request);
            }
            finally
            {
                boolean more;
                synchronized (QUEUE)
                {
                    more = !QUEUE.isEmpty() || !COMMENT_QUEUE.isEmpty() || !INDEX_QUEUE.isEmpty();
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

    /**
     * Функция конфигурации объекта строки EDT или элемента под указателем: метод модуля, его
     * экспортный образ, {@code ProposalElement} или {@code ParamSet} с ними; иначе {@code null}.
     * Прокси не разрешаются (как {@code edtDocumentationObjectProblem}).
     */
    static Target targetOf(Object info, int depth)
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
            return new Target(exported.getSourceUri().trimFragment(), exported.getName(), exported);
        return null;
    }

    /**
     * Запрос к индексу сборки для экспортной функции своего модуля: строка EDT несёт метод модуля,
     * а {@code ExportMethodTypeProvider.getMethodType} принимает экспортный образ. Ключ индекса
     * (по байткоду bsl 28.0.1) — FQN модуля из {@code getSourceUri}, имя метода и
     * {@code environments().toString()}; без записи возвращается собственный (пустой)
     * {@code getRetValType} — тогда расчёт модели. Согласовано с автором (#877, вариант А).
     */
    private static com._1c.g5.v8.dt.mcore.Method exportedLookup(com._1c.g5.v8.dt.bsl.model.Method method)
    {
        try
        {
            com._1c.g5.v8.dt.bsl.model.BslContextDefMethod lookup =
                com._1c.g5.v8.dt.bsl.model.BslContextDefFactory.eINSTANCE.createBslContextDefMethod();
            lookup.setName(method.getName());
            lookup.setSourceUri(EcoreUtil.getURI(method));
            lookup.setEnvironments(method.environments());
            lookup.setRetVal(true);
            return lookup;
        }
        catch (RuntimeException error)
        {
            log("error lookup method=" + method.getName() + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
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
        Object value = element.eGet(BslPackage.PROPOSAL_ELEMENT__LIST, false, false);
        if (!(value instanceof InternalEList<?> list))
            return null;
        var entries = list.basicIterator();
        while (entries.hasNext())
        {
            if (!(entries.next() instanceof com._1c.g5.v8.dt.bsl.model.FantomVariableOccurrence occurrence))
                continue;
            com._1c.g5.v8.dt.bsl.model.Variable variable = occurrence.getVariable();
            if (variable == null || variable.eIsProxy() || variable.getName() == null)
                return null;
            Resource resource = variable.eResource();
            if (resource == null || resource.getURI() == null)
                return null;
            com._1c.g5.v8.dt.bsl.model.Method method =
                org.eclipse.xtext.EcoreUtil2.getContainerOfType(variable, com._1c.g5.v8.dt.bsl.model.Method.class);
            String methodName = method != null && method.getName() != null
                ? method.getName().toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
            return new VariableTarget(resource.getURI().trimFragment(), methodName, variable.getName(),
                occurrence.getOffset());
        }
        return null;
    }

    /**
     * Тип переменной для показа или {@code null} — неизвестен. Итоговый — рассчитанный по
     * текущему состоянию модуля в той же точке; иначе — прежний рассчитанный как
     * предварительный, а расчёт модуля для этой точки ставится в голову очереди. UI-поток.
     */
    static State variableState(VariableTarget target)
    {
        if (target == null)
            return null;
        String stamp = currentStamp(target.module());
        VariableComputed computed = VARIABLE_RESULTS.get(target.key());
        if (computed != null && computed.stamp().equals(stamp) && computed.offset() == target.offset())
            return new State(computed.type(), true);
        requestPoint(target, stamp);
        return computed != null ? new State(computed.type(), false) : null;
    }

    private static void requestPoint(VariableTarget target, String stamp)
    {
        IFile file = ForeignModuleModel.fileOf(target.module());
        if (file == null || stamp == null || stamp.isEmpty())
            return;
        String point = target.method() + ":" + target.offset(); //$NON-NLS-1$
        String previous = POINTS.put(target.module(), point);
        if (point.equals(previous) && stateOf(target.module(), stamp).equals(MODULE_STAMPS.get(target.module())))
            return;
        enqueue(target.module(), file, ForeignModuleModel.openDocument(file), stamp, true);
    }

    /** Состояние модуля для очереди: штамп текста и точка переменных. */
    private static String stateOf(URI module, String stamp)
    {
        return stamp + "@" + POINTS.getOrDefault(module, ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Тип функции для показа или {@code null} — неизвестен. Итоговый ({@code fresh}) — рассчитанный
     * по текущему состоянию модуля. Иначе предварительный: из «Возвращаемое значение» комментария,
     * а без него — рассчитанный по прежнему состоянию; комментарии модуля для текущего состояния
     * при этом разбираются в фоне ({@link #requestComments}). Показ строки, подсказки; вне UI —
     * только чтение готового, без запросов расчёта.
     */
    static State state(Target target)
    {
        if (target == null)
            return null;
        String stamp = currentStamp(target.module());
        Computed computed = RESULTS.get(target.key());
        if (computed != null && computed.stamp().equals(stamp))
            return new State(computed.type(), true);
        // Вне UI (фоновый поток подсказки) — только чтение готового: запросы ставит UI-проход.
        boolean ui = Display.getCurrent() != null;
        if (ui && requestIndex(target, stamp, false))
            return computed != null ? new State(computed.type(), false) : null;
        if (ui)
            requestComments(target.module(), stamp);
        Computed comment = COMMENT_TYPES.get(target.key());
        if (comment != null && !comment.type().isEmpty())
            return new State(comment.type(), false);
        return computed != null ? new State(computed.type(), false) : null;
    }

    /** Разбор комментариев модуля для этого состояния, если его ещё не было. UI-поток. */
    private static void requestComments(URI module, String stamp)
    {
        if (stamp == null || stamp.isEmpty() || stamp.equals(COMMENT_STAMPS.get(module)))
            return;
        IFile file = ForeignModuleModel.fileOf(module);
        if (file == null)
            return;
        COMMENT_STAMPS.put(module, stamp);
        IXtextDocument document = ForeignModuleModel.openDocument(file);
        Request request = new Request(module, file, ForeignModuleModel.snapshot(document), stamp, null);
        synchronized (QUEUE)
        {
            COMMENT_QUEUE.removeIf(pending -> pending.module().equals(module));
            COMMENT_QUEUE.addLast(request);
        }
        log("comments module=" + module + " stamp=" + stamp); //$NON-NLS-1$ //$NON-NLS-2$
        WORKER.schedule();
    }

    /** Типы «Возвращаемое значение» всех функций модуля — по разбору без связывания (быстро). */
    private static void computeComments(Request request)
    {
        long start = System.nanoTime();
        Integer count = null;
        try
        {
            count = ForeignModuleModel.parse(request.module(), request.file(), request.text(),
                resource -> commentTypes(resource, request));
        }
        catch (Throwable error)
        {
            log("error comments module=" + request.module() + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
        }
        log("done comments module=" + request.module() + " functions=" + count //$NON-NLS-1$ //$NON-NLS-2$
            + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
        publish();
    }

    private static Integer commentTypes(Resource resource, Request request)
    {
        com._1c.g5.v8.dt.bsl.model.Module module = moduleOf(resource);
        if (module == null)
            return null;
        int count = 0;
        for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
        {
            if (!(method instanceof com._1c.g5.v8.dt.bsl.model.Function) || method.getName() == null)
                continue;
            String type;
            try
            {
                type = commentType(BslStructureInsertCommentTypes.parseMethodComment(method, module));
            }
            catch (RuntimeException error)
            {
                type = ""; //$NON-NLS-1$
            }
            COMMENT_TYPES.put(request.module() + "#" + method.getName().toLowerCase(Locale.ROOT), //$NON-NLS-1$
                new Computed(request.stamp(), type));
            count++;
        }
        return Integer.valueOf(count);
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
        return names.isEmpty() ? "" : "<" + String.join(", ", names) + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
    /** Только UI-поток. */
    private static final Map<URI, StampCheck> STAMP_CHECKS = new java.util.HashMap<>();

    /**
     * Модуль → документ его открытого редактора, найденный UI-потоком ({@code null} внутри ссылки —
     * не открыт). Для штампа вне UI: обход редакторов там невозможен, а штамп документа — простое
     * чтение поля.
     */
    private static final Map<URI, java.lang.ref.WeakReference<IXtextDocument>> KNOWN_DOCUMENTS =
        new ConcurrentHashMap<>();

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
        String stamp = ForeignModuleModel.stamp(file, document);
        STAMP_CHECKS.put(module, new StampCheck(stamp, now));
        return stamp;
    }

    /**
     * Выбор функции (строка списка, наведение): она в расчёте модуля — первая; модуль, ещё не
     * посчитанный для текущего состояния, встаёт в голову очереди. UI-поток.
     */
    static void activate(Target target)
    {
        if (target == null)
            return;
        String key = target.key();
        IFile file = ForeignModuleModel.fileOf(target.module());
        if (file == null)
        {
            log("skip noFile key=" + key); //$NON-NLS-1$
            RESULTS.putIfAbsent(key, new Computed("", "")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IXtextDocument document = ForeignModuleModel.openDocument(file);
        String stamp = ForeignModuleModel.stamp(file, document);
        Computed ready = RESULTS.get(key);
        if (ready != null && ready.stamp().equals(stamp))
            return;
        if (requestIndex(target, stamp, true))
            return;
        activateModel(target, file, document, stamp);
    }

    /** Тип по модели модуля: выбранная функция первой, модуль — в голову очереди. UI-поток. */
    private static void activateModel(Target target, IFile file, IXtextDocument document, String stamp)
    {
        String key = target.key();
        PRIORITY.put(target.module(), target.name().toLowerCase(Locale.ROOT));
        // Быстрый разбор комментариев — в очередь раньше полного расчёта: иначе запущенный
        // полный расчёт задерживает и предварительный тип (лог 08:40:53–08:41:03).
        requestComments(target.module(), stamp);
        Computed ready = RESULTS.get(key);
        if (ready != null && ready.stamp().equals(stamp))
            return;
        enqueue(target.module(), file, document, stamp, true);
    }

    /**
     * Экспортная функция немодифицированного модуля — тип из индекса сборки, как у штатной
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
        if (stamp.equals(INDEX_STAMPS.get(key)))
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
        INDEX_STAMPS.put(key, stamp);
        synchronized (QUEUE)
        {
            if (first)
                INDEX_QUEUE.addFirst(new IndexRequest(target, file, stamp));
            else
                INDEX_QUEUE.addLast(new IndexRequest(target, file, stamp));
        }
        WORKER.schedule();
        return true;
    }

    /**
     * Тип экспортной функции из индекса сборки — {@code ExportMethodTypeProvider.getMethodType}, тот
     * же источник, что у штатной подсказки EDT («*» — рассчитано системой типов). Индекс пуст —
     * расчёт модели модуля.
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
                    type = "<" + String.join(", ", names) + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        catch (Throwable error)
        {
            log("error index key=" + target.key() + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
        }
        log("index key=" + target.key() + " type=" + type //$NON-NLS-1$ //$NON-NLS-2$
            + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
        if (type != null)
        {
            Computed old = RESULTS.put(target.key(), new Computed(request.stamp(), type));
            if (old == null || !old.type().equals(type))
                publish();
            return;
        }
        // Индекс пуст — тип по модели модуля (как без индекса).
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() ->
            {
                IXtextDocument document = ForeignModuleModel.openDocument(request.file());
                String stamp = ForeignModuleModel.stamp(request.file(), document);
                if (stamp.equals(request.stamp()))
                    activateModel(target, request.file(), document, stamp);
            });
    }

    /**
     * Строка функции модуля редактора, в котором открыт список, показана, а модуль для текущего
     * состояния документа ещё не считался (в т.ч. после правок) — модуль в конец очереди
     * (модули выбранных функций — впереди). UI-поток (показ строки списка).
     */
    static void prefetchEditorModule(Target target)
    {
        if (target == null)
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        BslXtextEditor editor = reloader != null ? reloader.getBslEditor() : null;
        IFile file = editor != null && editor.getEditorInput() != null
            ? editor.getEditorInput().getAdapter(IFile.class) : null;
        IXtextDocument document = editor != null ? editor.getDocument() : null;
        if (file == null || document == null)
            return;
        String stamp = ForeignModuleModel.stamp(file, document);
        if (stateOf(target.module(), stamp).equals(MODULE_STAMPS.get(target.module()))
            || !file.equals(ForeignModuleModel.fileOf(target.module())))
            return;
        enqueue(target.module(), file, document, stamp, false);
    }

    /**
     * Модуль в очередь, если для этого состояния его расчёт не ждёт, не идёт и не сделан.
     * {@code first} — в голову очереди (модуль выбранной функции). UI-поток.
     */
    private static void enqueue(URI module, IFile file, IXtextDocument document, String stamp,
        boolean first)
    {
        int queued;
        synchronized (QUEUE)
        {
            Request pending = null;
            for (Request request : QUEUE)
            {
                if (request.module().equals(module))
                    pending = request;
            }
            String state = stateOf(module, stamp);
            boolean sameState = state.equals(MODULE_STAMPS.get(module));
            if (sameState && pending == null)
                return; // считается сейчас или уже посчитан
            if (pending != null && sameState && (!first || QUEUE.peekFirst() == pending))
                return;
            // Родная модель открытого редактора, в т.ч. активного (согласовано с автором, #877):
            // только чтение готовых состояний, как боковая подсказка EDT.
            IXtextDocument nativeDocument = document;
            Request request = pending != null && sameState ? pending
                : new Request(module, file, ForeignModuleModel.snapshot(document), stamp, nativeDocument);
            if (pending != null)
                QUEUE.remove(pending);
            if (first)
                QUEUE.addFirst(request);
            else
                QUEUE.addLast(request);
            MODULE_STAMPS.put(module, state);
            queued = QUEUE.size();
        }
        log("schedule module=" + module + " stamp=" + stamp + " openEditor=" + (document != null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " first=" + first + " queued=" + queued); //$NON-NLS-1$ //$NON-NLS-2$
        WORKER.schedule();
    }

    /**
     * Модуль, открытый в соседнем редакторе: сначала готовые типы его родной модели. Если готовы
     * все — отдельная модель не строится. Иначе — одна отдельная модель модуля (из снимка текста,
     * ввод не блокируется) на все его функции.
     */
    private static void compute(Request request)
    {
        long start = System.nanoTime();
        // Записанные из родной модели — запасной расчёт их не перезаписывает прежним состоянием.
        Set<String> nativeDone = ConcurrentHashMap.newKeySet();
        if (request.nativeDocument() != null)
        {
            Integer missing = null;
            // #region temp timing #877: ожидание блокировки документа против работы под ней
            long[] entered = new long[1];
            try
            {
                missing = request.nativeDocument().readOnly(resource ->
                {
                    entered[0] = System.nanoTime();
                    return readyFromNative(resource, request, nativeDone);
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
            if (missing != null && missing.intValue() == 0)
                return;
        }
        String source = null;
        try
        {
            source = ForeignModuleModel.read(request.module(), request.file(), request.text(),
                resource -> computeModule(resource, request, start, nativeDone)).source();
        }
        catch (Throwable error)
        {
            log("error module=" + request.module() + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
        }
        log("done module=" + request.module() + " source=" + source //$NON-NLS-1$ //$NON-NLS-2$
            + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
    }

    /**
     * Типы всех функций модуля по одной модели. Очередная — выбранная функция, если она ещё не
     * посчитана (выбор во время расчёта учитывается сразу); её тип публикуется немедленно,
     * остальные — перерисовкой не чаще {@link #REFRESH_INTERVAL_NS}.
     */
    private static Boolean computeModule(Resource resource, Request request, long start,
        Set<String> skipKeys)
    {
        com._1c.g5.v8.dt.bsl.model.Module module = moduleOf(resource);
        TypesComputer computer = module != null ? typesComputer(resource) : null;
        if (computer == null)
            return null;
        long modelMs = (System.nanoTime() - start) / 1_000_000L;
        computeVariables(module, request, request.stamp(), false, skipKeys);
        Map<String, com._1c.g5.v8.dt.bsl.model.Method> pending = new java.util.LinkedHashMap<>();
        for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
        {
            if (!(method instanceof com._1c.g5.v8.dt.bsl.model.Function) || method.getName() == null)
                continue;
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!skipKeys.contains(request.module() + "#" + name)) //$NON-NLS-1$
                pending.putIfAbsent(name, method);
        }
        int functions = pending.size();
        boolean dirty = false;
        long lastRefresh = System.nanoTime();
        while (!pending.isEmpty())
        {
            String priority = PRIORITY.get(request.module());
            boolean selected = priority != null && pending.containsKey(priority);
            String name = selected ? priority : pending.keySet().iterator().next();
            String type;
            try
            {
                type = typeOf(computer, pending.remove(name), resource);
            }
            catch (RuntimeException error)
            {
                log("error module=" + request.module() + " name=" + name + " " + error); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                type = ""; //$NON-NLS-1$
            }
            Computed old = RESULTS.put(request.module() + "#" + name, new Computed(request.stamp(), type)); //$NON-NLS-1$
            dirty |= old == null || !old.type().equals(type);
            if (selected)
                log("selected module=" + request.module() + " name=" + name + " type=" + type //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
            if (dirty && (selected || System.nanoTime() - lastRefresh > REFRESH_INTERVAL_NS))
            {
                publish();
                dirty = false;
                lastRefresh = System.nanoTime();
            }
        }
        if (dirty)
            publish();
        log("module=" + request.module() + " functions=" + functions + " modelMs=" + modelMs //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " typesMs=" + ((System.nanoTime() - start) / 1_000_000L - modelMs)); //$NON-NLS-1$
        return Boolean.TRUE;
    }

    /**
     * Готовые типы всех функций родной модели соседнего редактора — только чтение рассчитанных
     * EDT состояний ({@link BslDocCommentComputedTypes#readyNormalReturnTypes}): ни
     * {@code TypesComputer}, ни разрешения прокси под блокировкой его документа. Функция без
     * готового состояния или с типом-прокси не записывается.
     *
     * @return число функций без готового типа; {@code null} — модели нет
     */
    private static Integer readyFromNative(Resource resource, Request request, Set<String> done)
    {
        com._1c.g5.v8.dt.bsl.model.Module module = moduleOf(resource);
        if (module == null)
            return null;
        // Штамп — того состояния, которое читается сейчас, под блокировкой.
        String stamp = ForeignModuleModel.stamp(request.file(), request.nativeDocument());
        // Переменные — первыми: их строки видны первыми.
        int missing = computeVariables(module, request, stamp, true, done);
        boolean dirty = false;
        int noState = 0;
        int emptyState = 0;
        int proxyType = 0;
        String proxyExample = null;
        List<String> missingExamples = new java.util.ArrayList<>();
        for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
        {
            if (!(method instanceof com._1c.g5.v8.dt.bsl.model.Function) || method.getName() == null)
                continue;
            List<?> ready = BslDocCommentComputedTypes.readyNormalReturnTypes(method);
            Set<String> names = new LinkedHashSet<>();
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
                    String name = item instanceof TypeItem type && !type.eIsProxy() ? typeName(type) : null;
                    if (name == null || name.isEmpty())
                    {
                        complete = false;
                        proxyType++;
                        if (proxyExample == null)
                            proxyExample = method.getName() + ":" + item; //$NON-NLS-1$
                        break;
                    }
                    names.add(name);
                }
            }
            if (!complete)
            {
                missing++;
                if (missingExamples.size() < 5)
                    missingExamples.add(method.getName());
                continue;
            }
            String type = "<" + String.join(", ", names) + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String key = request.module() + "#" + method.getName().toLowerCase(Locale.ROOT); //$NON-NLS-1$
            Computed old = RESULTS.put(key, new Computed(stamp, type));
            done.add(key);
            dirty |= old == null || !old.type().equals(type);
        }
        log("native reasons module=" + request.module() + " noState=" + noState + " emptyState=" + emptyState //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " proxyType=" + proxyType + " proxyExample=" + proxyExample + " examples=" + missingExamples); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (dirty)
            publish();
        return Integer.valueOf(missing);
    }

    /**
     * Типы переменных метода точки ({@link #POINTS}) и переменных модуля в этой точке.
     * Публикуются сразу: строки переменных видны первыми.
     *
     * @param readyOnly родная модель редактора: только готовое состояние NORMAL в точке
     *            ({@code getTypeStateProvider().get(NORMAL).getNearestByOffset} — так строку
     *            переменной строит сама EDT), без досчёта и разрешения прокси; иначе отдельная
     *            модель — штатный {@code TypeSystemUtil.getVariableTypesAtOffset}
     * @param done переменные, записанные из родной модели: в отдельной модели пропускаются,
     *            в родной — пополняются
     * @return число переменных без готового типа (в режиме {@code readyOnly})
     */
    private static int computeVariables(com._1c.g5.v8.dt.bsl.model.Module module, Request request,
        String stamp, boolean readyOnly, Set<String> done)
    {
        String point = POINTS.get(request.module());
        int colon = point != null ? point.lastIndexOf(':') : -1;
        if (colon < 0)
            return 0;
        String methodName = point.substring(0, colon);
        int offset;
        try
        {
            offset = Integer.parseInt(point.substring(colon + 1));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
        long start = System.nanoTime();
        com._1c.g5.v8.dt.bsl.model.Method method = null;
        for (com._1c.g5.v8.dt.bsl.model.Method candidate : module.allMethods())
        {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(methodName))
            {
                method = candidate;
                break;
            }
        }
        com._1c.g5.v8.dt.mcore.util.Environments environments =
            method != null ? method.environments() : module.environments();
        int count = 0;
        int missing = 0;
        boolean dirty = false;
        // Переменные модуля — без переменных методов; переменные метода — все его.
        List<com._1c.g5.v8.dt.bsl.model.Variable> variables = new java.util.ArrayList<>();
        for (org.eclipse.emf.ecore.EObject child : module.eContents())
        {
            if (!(child instanceof com._1c.g5.v8.dt.bsl.model.Method))
                variables.addAll(org.eclipse.xtext.EcoreUtil2.getAllContentsOfType(child,
                    com._1c.g5.v8.dt.bsl.model.Variable.class));
            if (child instanceof com._1c.g5.v8.dt.bsl.model.Variable variable)
                variables.add(variable);
        }
        int moduleVariables = variables.size();
        if (method != null)
            variables.addAll(org.eclipse.xtext.EcoreUtil2.getAllContentsOfType(method,
                com._1c.g5.v8.dt.bsl.model.Variable.class));
        for (int i = 0; i < variables.size(); i++)
        {
            com._1c.g5.v8.dt.bsl.model.Variable variable = variables.get(i);
            if (variable.getName() == null)
                continue;
            String owner = i < moduleVariables ? "" : methodName; //$NON-NLS-1$
            String key = new VariableTarget(request.module(), owner, variable.getName(), offset).key();
            if (!readyOnly && done.contains(key))
                continue;
            String type;
            if (readyOnly)
            {
                type = readyVariableType(variable, offset);
                if (type == null)
                {
                    missing++;
                    continue;
                }
                done.add(key);
            }
            else
                type = computedVariableType(variable, offset, environments, module);
            VariableComputed old = VARIABLE_RESULTS.put(key, new VariableComputed(stamp, offset, type));
            dirty |= old == null || !old.type().equals(type) || old.offset() != offset;
            count++;
        }
        log("variables module=" + request.module() + " method=" + methodName + " offset=" + offset //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " ready=" + readyOnly + " count=" + count + " missing=" + missing //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " ms=" + (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
        if (dirty)
            publish();
        return missing;
    }

    /**
     * Готовый тип переменной в точке — только чтение сохранённых EDT состояний, как в
     * {@code BslProposalProvider.createProposalsByDeclarementVariables}; {@code null} — готового
     * нет (состояний нет, типов нет или есть тип-прокси).
     */
    private static String readyVariableType(com._1c.g5.v8.dt.bsl.model.Variable variable, int offset)
    {
        try
        {
            com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProviderCollector collector =
                variable.getTypeStateProvider();
            com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProvider provider = collector != null
                ? collector.get(com._1c.g5.v8.dt.bsl.model.typesytem.TypeSystemMode.NORMAL) : null;
            List<com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeState> states =
                provider != null ? provider.getNearestByOffset(offset) : null;
            if (states == null || states.isEmpty())
                return null;
            Set<String> names = new LinkedHashSet<>();
            for (com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeState state : states)
            {
                for (TypeItem item : state.getTypes())
                {
                    String name = item != null && !item.eIsProxy() ? typeName(item) : null;
                    if (name == null || name.isEmpty())
                        return null;
                    names.add(name);
                }
            }
            return names.isEmpty() ? null : "<" + String.join(", ", names) + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (RuntimeException error)
        {
            return null;
        }
    }

    /** Тип переменной в точке по рассчитанной отдельной модели (штатный расчёт EDT). */
    private static String computedVariableType(com._1c.g5.v8.dt.bsl.model.Variable variable, int offset,
        com._1c.g5.v8.dt.mcore.util.Environments environments, com._1c.g5.v8.dt.bsl.model.Module module)
    {
        try
        {
            List<TypeItem> types = com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil
                .getVariableTypesAtOffset(variable, offset, environments);
            Set<String> names = new LinkedHashSet<>();
            if (types != null)
            {
                for (TypeItem item : types)
                {
                    TypeItem resolved = item != null && item.eIsProxy()
                        ? (TypeItem)EcoreUtil.resolve(item, module) : item;
                    String name = typeName(resolved);
                    if (name != null && !name.isEmpty())
                        names.add(name);
                }
            }
            return names.isEmpty() ? "" : "<" + String.join(", ", names) + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        catch (RuntimeException error)
        {
            log("error variable=" + variable.getName() + " " + error); //$NON-NLS-1$ //$NON-NLS-2$
            return ""; //$NON-NLS-1$
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
            ContentAssistPopupSync.refreshProposalTable(assistant);
        LISTENERS.removeIf(listener -> !listener.getAsBoolean());
    }

    private static com._1c.g5.v8.dt.bsl.model.Module moduleOf(Resource resource)
    {
        return resource instanceof XtextResource && !resource.getContents().isEmpty()
            && resource.getContents().get(0) instanceof com._1c.g5.v8.dt.bsl.model.Module module
            ? module : null;
    }

    private static TypesComputer typesComputer(Resource resource)
    {
        IResourceServiceProvider rsp = ((XtextResource)resource).getResourceServiceProvider();
        return rsp != null ? rsp.get(TypesComputer.class) : null;
    }

    private static String typeOf(TypesComputer computer, com._1c.g5.v8.dt.bsl.model.Method method,
        Resource resource)
    {
        // Как EDT для методов модуля в BslProposalProvider.createProposalsByDeclarementMethods.
        List<TypeItem> types = computer.computeTypes(method, method.environments());
        Set<String> names = new LinkedHashSet<>();
        for (TypeItem item : types)
        {
            TypeItem resolved = item != null && item.eIsProxy()
                ? (TypeItem)EcoreUtil.resolve(item, resource) : item;
            String typeName = typeName(resolved);
            if (typeName != null && !typeName.isEmpty())
                names.add(typeName);
        }
        return names.isEmpty() ? "" : "<" + String.join(", ", names) + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static String typeName(TypeItem type)
    {
        if (type == null || type.eIsProxy())
            return null;
        if (type instanceof DuallyNamedElement dually && dually.getNameRu() != null
            && !dually.getNameRu().isEmpty())
            return SmartContentAssistProcessor.localizeAssistTypeSegment(dually.getNameRu());
        return type.getName() != null
            ? SmartContentAssistProcessor.localizeAssistTypeSegment(type.getName()) : null;
    }

    /** Временный лог (снять после подтверждения). */
    private static void log(String text)
    {
        Global.tempLog(LOG, text);
    }
}
