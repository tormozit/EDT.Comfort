package tormozit;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.SyntaxErrorMessage;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.MarkerUpdateBatch;
import com._1c.g5.v8.dt.validation.marker.PlainEObjectMarker;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.DirectLocation;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

/**
 * Проверка «Обрыв разбора модуля»: синтаксическая ошибка, после которой разбор не восстановился
 * и остаток текста в синтаксическое дерево не попал.
 *
 * <p>Такая ошибка качественно отличается от обычной синтаксической: после неё методов ниже места
 * обрыва в модели нет, поэтому по ним не работает ни одна другая проверка, а «чисто» в панели
 * «Проблемы» означает лишь то, что проверять было нечего. Признак обрыва считает
 * {@link BslAstCompleteness} — тот же, по которому {@link BslParseTruncationMarkHook} метит
 * место обрыва в самом редакторе.
 *
 * <p>Своя проверка нужна не только ради текста проблемы: у неё есть <b>идентификатор</b>, и по
 * нему {@link GitBaselineFilterHook} отклоняет скрытие этой проблемы механизмом ЕДТ «скрыть
 * языковые проблемы из базовой ветки git». Обрыв дерева обесценивает результаты проверок
 * всего модуля, и прятать его как «унаследованный от базовой ветки» нельзя.
 *
 * <p>Стоимость: у модуля без синтаксических ошибок — одно поле результата разбора
 * ({@code hasSyntaxErrors}), обход узловой модели включается только при уже имеющихся ошибках и
 * кешируется на результате разбора (см. {@code BslAstCompleteness.ANALYSIS_CACHE}). Своего
 * разбора проверка не запускает — читает результат разбора того модуля, который валидация и так
 * держит в руках.
 */
public class BslAstTruncationCheck
    extends BasicCheck<Object>
{
    /**
     * Идентификатор проверки. У маркера в панели {@code checkId} — короткий UID проекта
     * ({@code SU…}); {@link GitBaselineFilterHook} распознаёт и его, и этот длинный идентификатор.
     */
    public static final String CHECK_ID = "comfort-bsl-ast-truncation"; //$NON-NLS-1$

    private static final String TITLE = "Обрыв разбора модуля"; //$NON-NLS-1$

    private static final String DESCRIPTION =
        "Синтаксическая ошибка, после которой разбор модуля не восстановился: код ниже места ошибки" //$NON-NLS-1$
            + " в синтаксическое дерево не попал. Методы, оставшиеся за местом обрыва, не проверяются" //$NON-NLS-1$
            + " ни одной другой проверкой, не видны в структуре модуля и не участвуют в поиске ссылок," //$NON-NLS-1$
            + " поэтому отсутствие других проблем в таком модуле ничего не значит."; //$NON-NLS-1$

    private static final String MESSAGE =
        "Синтаксическая ошибка оборвала разбор модуля: код ниже этого места в синтаксическое дерево" //$NON-NLS-1$
            + " не попал, остальные проверки по модулю не выполнены"; //$NON-NLS-1$

    @Override
    public String getCheckId()
    {
        return CHECK_ID;
    }

    @Override
    protected void configureCheck(CheckConfigurer builder)
    {
        builder.title(TITLE)
            .description(DESCRIPTION)
            .complexity(CheckComplexity.NORMAL)
            .severity(IssueSeverity.CRITICAL)
            .issueType(IssueType.ERROR)
            .module()
            .checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor resultAceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (monitor.isCanceled() || !(object instanceof Module module))
            return;

        Resource resource = module.eResource();
        if (!(resource instanceof XtextResource xtextResource))
            return;

        String resourceUri = xtextResource.getURI() != null ? xtextResource.getURI().toString() : null;

        IParseResult parseResult = xtextResource.getParseResult();
        INode error = BslAstCompleteness.isTruncatedConfirmed(parseResult)
            ? BslAstCompleteness.truncatingError(parseResult) : null;
        if (error == null)
        {
            GitBaselineFilterHook.forgetIssue(resourceUri);
            TruncationMarkers.clear(module);
            return;
        }

        /*
         * Отбор по базовой ветке в пути Issue смотрит только файл и строку, поэтому отметка
         * исключения должна стоять до публикации маркера, а не когда-нибудь потом.
         */
        DirectLocation location = location(parseResult, error, module);
        GitBaselineFilterHook.ensureInstalled();
        GitBaselineFilterHook.exemptIssue(resourceUri, location.getLineNumber().intValue());
        TruncationMarkers.publish(module, message(error), location);
    }

    /**
     * Маркер проблемы «Обрыв разбора модуля» плагин пишет в хранилище сам.
     *
     * <p>Штатный {@code addIssue} не вызываем: EDT тогда сама пишет маркер проверки (другая
     * картинка, другой ключ источника). Даже одну такую запись не нужно — в панели только наш
     * маркер, который пишется напрямую.
     *
     * <p>{@code setMarkers(проект, ключ, маркеры)} удаляет прежние по {@code OBJECT_ID} маркера,
     * а не по ключу вызова. У {@link PlainEObjectMarker} это путь файла, поэтому вызов с нашей
     * приставкой прежних не находит и каждый проход проверки добавляет ещё одну копию. Замена —
     * {@link MarkerUpdateBatch}: снятие по паре «объект + источник + код проверки», затем одна
     * запись. Приставка {@link #OBJECT_ID_PREFIX} остаётся ключом <b>источника</b>, чтобы не
     * снести штатные проблемы того же файла.
     *
     * <p>Скрытие по базовой ветке этому маркеру не грозит: {@link GitBaselineFilterHook} отклоняет
     * скрытие и по короткому UID, и по {@link #CHECK_ID}.
     */
    private static final class TruncationMarkers
    {
        /**
         * Тип источника — штатный тип маркеров BSL ({@code BslBmAwareResourceValidatorListener
         * .MARKER_SOURCE}). Свой тип не годится: провайдер, который панель ищет для маркера,
         * подбирается именно по типу источника, и для незнакомого получается «Provider not found
         * for marker». Маркер при этом остаётся нашим — он отличается ключом источника и кодом
         * проверки, а тип лишь говорит, как его читать.
         */
        private static final String SOURCE_TYPE = "BslEditor"; //$NON-NLS-1$

        private static final String OBJECT_ID_PREFIX = "comfort-ast-truncation:"; //$NON-NLS-1$

        /** Модули, для которых маркер сейчас записан. Нужен, чтобы не снимать несуществующий. */
        private static final java.util.Set<String> PUBLISHED = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** Пересчёт индексов маркеров делается один раз за сеанс: он идёт по всему проекту. */
        private static final java.util.concurrent.atomic.AtomicBoolean REINDEXED =
            new java.util.concurrent.atomic.AtomicBoolean();

        /** Проекты, для которых состав уже прочитан из хранилища (маркеры переживают перезапуск). */
        private static final java.util.Set<String> LOADED = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** Не больше одной отложенной записи на модуль: проверка зовёт несколько проходов сразу. */
        private static final java.util.concurrent.ConcurrentHashMap<String, Job> JOBS =
            new java.util.concurrent.ConcurrentHashMap<>();

        private TruncationMarkers()
        {
        }

        static void publish(Module module, String message, DirectLocation location)
        {
            org.eclipse.emf.common.util.URI uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(module);
            if (uri == null || !uri.isPlatformResource())
                return;
            isPublished(uri);
            schedule(uri, "Комфорт: маркер обрыва разбора модуля", (manager, project) -> { //$NON-NLS-1$
                apply(manager, project, uri, new Marker[] {marker(project, uri, message, location)});
                PUBLISHED.add(uri.toString());
                /*
                 * Значение индекса BRANCH_CHANGES маркер получает при записи и дальше берётся из
                 * хранилища. Если оно посчиталось как «есть в базовой ветке», отбор панели прячет
                 * маркер, и никакое исключение в службе на это уже не влияет — её просто не
                 * спрашивают. Пересчёт заставляет посчитать значение заново, уже с исключением по
                 * коду проверки. Раз за сеанс: операция по всему проекту.
                 */
                if (REINDEXED.compareAndSet(false, true))
                    manager.reindex(project, new org.eclipse.core.runtime.NullProgressMonitor());
            });
        }

        static void clear(Module module)
        {
            org.eclipse.emf.common.util.URI uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(module);
            if (uri == null || !uri.isPlatformResource())
                return;
            isPublished(uri);
            schedule(uri, "Комфорт: снятие маркера обрыва разбора модуля", (manager, project) -> { //$NON-NLS-1$
                apply(manager, project, uri, new Marker[0]);
                PUBLISHED.remove(uri.toString());
            });
        }

        /**
         * Был ли маркер записан. Состав хранилища читается один раз на проект: маркеры переживают
         * перезапуск EDT, и без этого чтения маркер, записанный в прошлом сеансе, снять было бы
         * некому. Запрос идёт по индексу кода проверки, то есть дёшев.
         */
        private static boolean isPublished(org.eclipse.emf.common.util.URI uri)
        {
            String projectName = uri.segmentCount() > 1 ? uri.segment(1) : null;
            if (projectName != null && LOADED.add(projectName))
                loadPublished(projectName);
            return PUBLISHED.contains(uri.toString());
        }

        private static void loadPublished(String projectName)
        {
            try
            {
                org.eclipse.core.resources.IProject project = project(projectName);
                IMarkerManagerV2 manager = ServiceAccess.get(IMarkerManagerV2.class);
                if (project == null || manager == null)
                    return;
                java.util.List<Marker> stored = stored(manager, project);
                for (Marker marker : stored)
                {
                    if (!(marker instanceof PlainEObjectMarker plain) || plain.getURI() == null)
                        continue;
                    /*
                     * Маркеры прежней записи — с несуществующим типом источника. Панель ищет
                     * провайдер по типу источника и на таком маркере валится с «Provider not found
                     * for marker», причём при каждой загрузке: маркер лежит в хранилище и правка
                     * кода его не исправляет. Удаляем — следующая проверка модуля запишет годный.
                     */
                    if (!SOURCE_TYPE.equals(marker.getSourceType()))
                        apply(manager, project, plain.getURI(), new Marker[0]);
                    else
                        PUBLISHED.add(plain.getURI().toString());
                }
            }
            catch (Exception | LinkageError e)
            {
                LOADED.remove(projectName);
            }
        }

        /** Запись идёт вне потока валидации: хранилище берёт блокировку снимка. */
        private static void schedule(org.eclipse.emf.common.util.URI uri, String name, MarkerWrite write)
        {
            String projectName = uri.segmentCount() > 1 ? uri.segment(1) : null;
            if (projectName == null)
                return;
            String jobKey = uri.trimFragment().toString();
            Job job = new Job(name)
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    try
                    {
                        org.eclipse.core.resources.IProject project = project(projectName);
                        IMarkerManagerV2 manager = ServiceAccess.get(IMarkerManagerV2.class);
                        if (project != null && manager != null)
                        {
                            synchronized (TruncationMarkers.class)
                            {
                                if (!monitor.isCanceled())
                                    write.run(manager, project);
                            }
                        }
                    }
                    catch (Exception | LinkageError e)
                    {
                    }
                    finally
                    {
                        JOBS.remove(jobKey, this);
                    }
                    return Status.OK_STATUS;
                }
            };
            Job previous = JOBS.put(jobKey, job);
            if (previous != null)
                previous.cancel();
            job.setSystem(true);
            job.schedule();
        }

        /**
         * Один маркер на модуль: сначала снимает все уже лежащие с этим кодом проверки по этому
         * файлу (любой ключ источника), затем при необходимости пишет один новый.
         */
        private static void apply(IMarkerManagerV2 manager, org.eclipse.core.resources.IProject project,
            org.eclipse.emf.common.util.URI uri, Marker[] next)
        {
            String fileId = fileId(uri);
            if (fileId == null)
                return;
            String sourceKey = sourceKey(uri);
            java.util.Map<String, Object[]> tasks = new java.util.LinkedHashMap<>();
            long keepCreated = 0;
            for (Marker stored : stored(manager, project))
            {
                if (!sameModule(stored, fileId))
                    continue;
                Object source = stored.getSourceObjectId();
                Object object = stored.getMarkerObjectId();
                if (object == null)
                    object = fileId;
                String checkId = stored.getCheckId() == null || stored.getCheckId().isBlank() ? CHECK_ID
                    : stored.getCheckId();
                tasks.put(taskKey(source, object, checkId), new Object[] { source, object, null, checkId });
                if (sourceKey.equals(source) && stored.getCreatedAt() > 0)
                    keepCreated = stored.getCreatedAt();
            }
            if (next != null && next.length > 0)
            {
                if (keepCreated > 0)
                    next[0].setCreatedAt(keepCreated);
                String checkId = next[0].getCheckId() == null || next[0].getCheckId().isBlank() ? CHECK_ID
                    : next[0].getCheckId();
                tasks.put(taskKey(sourceKey, fileId, checkId), new Object[] { sourceKey, fileId, next, checkId });
            }
            if (tasks.isEmpty())
                return;
            MarkerUpdateBatch batch = new MarkerUpdateBatch(tasks.size());
            for (Object[] task : tasks.values())
                batch.addNewMarkers(task[0], task[1], (String)task[3], (Marker[])task[2]);
            manager.setMarkers(project, batch);
        }

        private static java.util.List<Marker> stored(IMarkerManagerV2 manager,
            org.eclipse.core.resources.IProject project)
        {
            java.util.List<Marker> found = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String checkId : checkIds(project))
            {
                for (Marker marker : manager.createReader(java.util.List.of(project), true)
                    .markers(MarkerFilter.createCheckFilter(checkId))
                    .collect(java.util.stream.Collectors.toList()))
                {
                    String key = String.valueOf(marker.getSourceObjectId()) + '\0'
                        + marker.getMarkerObjectId() + '\0' + marker.getCheckId();
                    if (seen.add(key))
                        found.add(marker);
                }
            }
            return found;
        }

        /** Длинный идентификатор (хвосты прошлой записи) и короткий UID проекта ({@code SU…}). */
        private static java.util.List<String> checkIds(org.eclipse.core.resources.IProject project)
        {
            java.util.List<String> ids = new java.util.ArrayList<>();
            ids.add(CHECK_ID);
            String shortUid = shortUid(project);
            if (shortUid != null && !CHECK_ID.equals(shortUid))
                ids.add(shortUid);
            return ids;
        }

        private static boolean sameModule(Marker marker, String fileId)
        {
            if (fileId.equals(marker.getMarkerObjectId()) || fileId.equals(marker.getMarkerId()))
                return true;
            if (marker instanceof PlainEObjectMarker plain && plain.getURI() != null)
            {
                String path = plain.getURI().trimFragment().toPlatformString(true);
                if (fileId.equals(path))
                    return true;
            }
            Object extra = StandardExtraInfo.TEXT_URI_TO_PROBLEM.get(marker);
            if (extra != null && extra.toString().contains(fileId))
                return true;
            String presentation = marker.getObjectPresentation();
            String moduleName = moduleNameFromFile(fileId);
            return moduleName != null && presentation != null && presentation.contains(moduleName);
        }

        /** Имя модуля из пути {@code …/CommonModules/<Имя>/Module.bsl}. */
        private static String moduleNameFromFile(String fileId)
        {
            int slash = fileId.lastIndexOf('/');
            if (slash <= 0)
                return null;
            int prev = fileId.lastIndexOf('/', slash - 1);
            return prev < 0 ? null : fileId.substring(prev + 1, slash);
        }

        private static String taskKey(Object source, Object object, String checkId)
        {
            return String.valueOf(source) + '\0' + object + '\0' + checkId;
        }

        /** Ключ источника: свой, чтобы снятие не трогало штатные маркеры того же файла. */
        private static String sourceKey(org.eclipse.emf.common.util.URI uri)
        {
            return OBJECT_ID_PREFIX + fileId(uri);
        }

        /** Идентификатор объекта маркера — путь файла, как у {@link PlainEObjectMarker}. */
        private static String fileId(org.eclipse.emf.common.util.URI uri)
        {
            return uri.trimFragment().toPlatformString(true);
        }

        private static org.eclipse.core.resources.IProject project(String projectName)
        {
            org.eclipse.core.resources.IProject project =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            return project != null && project.isAccessible() ? project : null;
        }

        /**
         * Маркер той же формы, что делает из проблемы сама EDT
         * ({@code BmAwareResourceValidatorListener.createMarker}): ссылка на объект модуля, место в
         * тексте — в дополнительных сведениях.
         */
        private static Marker marker(org.eclipse.core.resources.IProject project,
            org.eclipse.emf.common.util.URI uri, String message, DirectLocation location)
        {
            PlainEObjectMarker marker = new PlainEObjectMarker();
            marker.setUri(uri);
            marker.setSourceObjectId(sourceKey(uri));
            marker.setMessage(message);
            marker.setSeverity(MarkerSeverity.CRITICAL);
            marker.setSourceType(SOURCE_TYPE);
            /*
             * Короткий UID проекта ({@code SU…}), как у штатного маркера проверки. По нему панель
             * берёт тип (иконка «Ошибка») и открывает настройку проверки по двойному щелчку.
             */
            String checkId = shortUid(project);
            marker.setCheckId(checkId != null ? checkId : CHECK_ID);
            marker.setCreatedAt(System.currentTimeMillis());
            marker.setProject(project);

            java.util.Map<String, String> extra = new java.util.HashMap<>();
            StandardExtraInfo.TEXT_LINE.put(extra, location.getLineNumber());
            StandardExtraInfo.TEXT_OFFSET.put(extra, location.getOffset());
            StandardExtraInfo.TEXT_LENGTH.put(extra, location.getLength());
            StandardExtraInfo.TEXT_URI_TO_PROBLEM.put(extra, uri.toString());
            StandardExtraInfo.TEXT_SYNTAX_ERROR.put(extra, Boolean.FALSE);
            marker.setExtraInfo(extra);
            return marker;
        }

        /**
         * Короткий код проверки в проекте ({@code SU…}). Панель и {@code getUidForShortUid}
         * ждут именно его, а не {@link #CHECK_ID}.
         */
        private static String shortUid(org.eclipse.core.resources.IProject project)
        {
            ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
            if (repository == null || project == null)
                return null;
            java.util.Set<CheckUid> uids = repository.getCheckUidForCheckId(CHECK_ID, project);
            if (uids == null || uids.isEmpty())
                return null;
            return repository.getShortUid(uids.iterator().next(), project);
        }

        /** Что сделать с хранилищем: у записи и снятия различается только массив маркеров. */
        private interface MarkerWrite
        {
            void run(IMarkerManagerV2 manager, org.eclipse.core.resources.IProject project);
        }
    }

    /**
     * Место проблемы — узел оборвавшей ошибки, как у пометки в редакторе
     * ({@code BslParseTruncationMarkHook.toMark}): смещение узла и его длина, но не меньше
     * одного символа.
     *
     * <p>Номер строки ЕДТ пересчитает из смещения сама, но конструктор его принимает — считаем
     * тем же способом, что и Xtext, чтобы в отчётах не разъезжалось.
     */
    private static DirectLocation location(IParseResult parseResult, INode error, Module module)
    {
        ICompositeNode rootNode = parseResult.getRootNode();
        int offset = error.getOffset();
        int line = rootNode != null ? NodeModelUtils.getLineAndColumn(rootNode, offset).getLine() : 1;
        return new DirectLocation(Integer.valueOf(offset), Integer.valueOf(Math.max(1, error.getLength())),
            Integer.valueOf(line), causer(error, module));
    }

    /**
     * Объект-виновник — обязательно <b>внутри</b> модуля, а не сам модуль.
     *
     * <p>{@code CheckExecutor} при построении диагностики отбрасывает целиком все проблемы,
     * привязанные к объекту, который лежит в BM ({@code key instanceof IBmObject &&
     * bmGetEngine() != null}). В проходе глубокого анализа — том самом, что наполняет хранилище
     * маркеров, — модуль взят из BM, и его корень как раз такой объект. Без виновника
     * {@code CheckExecutor} привязывает проблему к проверяемому объекту, то есть к модулю, и она
     * молча пропадает: в редакторе (там модуль не из BM) видна, а в панели «Проблемы» — нет.
     *
     * <p>Место проблемы от выбора виновника не зависит: смещение и длину несёт сам
     * {@link DirectLocation}.
     */
    private static EObject causer(INode error, Module module)
    {
        EObject semantic = NodeModelUtils.findActualSemanticObjectFor(error);
        if (semantic != null && semantic != module)
            return semantic;

        List<Method> methods = module.allMethods();
        return methods.isEmpty() ? null : methods.get(methods.size() - 1);
    }

    /** К общему тексту добавляется формулировка самого разбора — чтобы не искать её отдельно. */
    private static String message(INode error)
    {
        SyntaxErrorMessage syntaxError = error.getSyntaxErrorMessage();
        String detail = syntaxError != null ? syntaxError.getMessage() : null;
        return detail != null && !detail.isBlank() ? MESSAGE + ". Ошибка разбора: " + detail : MESSAGE; //$NON-NLS-1$
    }
}
