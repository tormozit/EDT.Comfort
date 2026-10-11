package tormozit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.search.core.text.TextSearchModelMatch;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Ручная проверка орфографии в текстах пользователя (issue #757): многоязычные строки и
 * наименования предопределённых элементов модели, литералы-аргументы {@code НСтр()} модулей.
 * Состав текстов — тот же, что у пометки «Строки пользовательского интерфейса» поиска по
 * конфигурации ({@link #isUserText}, {@link ConfigSearchDialogHook#isInsideNStrLiteral}).
 * Ошибки ищет {@link ComfortSpellingEngine#findMisspelledRanges}; результат — в панели «Поиск»
 * в режиме «Ошибки орфографии» ({@link CompareSearchResult#isSpelling}).
 */
final class UserTextSpellCheck
{
    static final String TITLE = "Проверить орфографию в текстах пользователя";

    private static final String JOB_NAME = "Проверка орфографии в текстах пользователя";
    private static final String LOG_TOPIC = "spell-user-texts";
    private static final String BSL_EXTENSION = "bsl";
    /** Текст длиннее показывается фрагментом вокруг слова с ошибкой. */
    private static final int FRAGMENT_LIMIT = 160;
    private static final int FRAGMENT_CONTEXT = 60;
    /** Вызов {@code НСтр("…")}; принадлежность коду, а не комментарию или строке, сверяется отдельно. */
    private static final Pattern NSTR_CALL = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:НСтр|NStr)\\s*\\(\\s*\"",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Map<EClass, List<EAttribute>> TEXT_ATTRIBUTES = new ConcurrentHashMap<>();

    /**
     * Реквизит модели с текстом, который видит пользователь: строка многоязычного свойства либо
     * наименование предопределённого элемента.
     */
    static boolean isUserText(EAttribute attribute)
    {
        return attribute.eContainer() == McorePackage.Literals.LOCAL_STRING_MAP_ENTRY
            || attribute == MdClassPackage.Literals.PREDEFINED_ITEM__DESCRIPTION;
    }

    static void findInProject(IProject project)
    {
        schedule(project, null, null);
    }

    /**
     * Проверка только выбранных объектов с вложенными; пустой набор не расширяется до проекта.
     *
     * @param scope подписи выбранных узлов для заголовка результата
     */
    static void findInObjects(IProject project, List<URI> roots, String scope)
    {
        schedule(project, List.copyOf(roots), scope);
    }

    /** Открывает строку результата тем же путём, что и результаты поиска по конфигурации. */
    static void open(CompareSearchMatch match)
    {
        IWorkbenchPage page = Global.getActivePage();
        String word = match.getMisspelledWord();
        Global.tempLog(LOG_TOPIC, "open word=" + word + " module=" + match.getModuleFile()
            + " offset=" + match.getModuleOffset() + " model=" + (match.getModelMatch() != null));
        if (match.getModuleFile() != null)
            ConfigSearchResultsHook.openFileMatch(match.getModuleFile(), match.getModuleOffset(),
                word != null ? word.length() : 0, page);
        else
            ConfigSearchResultsHook.openModelMatch(match.getModelMatch(), page);
    }

    /**
     * Словарь изменился (слово добавлено, отменено добавление, файл перечитан): из результатов
     * проверки убираются ошибки слов, которые словарь теперь принимает, и панели «Поиск»
     * перечитывают свои строки. Слово сверяется тем же {@link ComfortSpellingEngine#isCorrect},
     * отказ которого и дал ошибку, — поэтому уходят и остальные формы добавленного слова.
     * Отмена добавления строки не возвращает: для этого проверку нужно запустить заново.
     */
    static void onUserDictionaryChanged()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            for (CompareSearchResult result : new ArrayList<>(RESULTS))
            {
                Map<String, Boolean> accepted = new HashMap<>();
                // Проектный словарь — проекта самого результата, а не активного в этот момент.
                ComfortSpellingEngine.setCheckProjectOverride(result.getProject());
                boolean removed;
                try
                {
                    removed = result.getMatches().removeIf(match -> match.getMisspelledWord() != null
                        && accepted.computeIfAbsent(match.getMisspelledWord(), ComfortSpellingEngine::isCorrect));
                }
                finally
                {
                    ComfortSpellingEngine.setCheckProjectOverride(null);
                }
                Global.tempLog(LOG_TOPIC, "dictionary changed project=" + result.getProject().getName()
                    + " words=" + accepted.size() + " removed=" + removed + " left=" + result.getMatches().size());
                if (removed)
                    CompareSearchResultPage.refreshResult(result);
            }
        });
    }

    /**
     * Команда «Добавить в словарь» строки результата: тот же диалог, что в редакторе модуля
     * (общий или проектный словарь, морфология). Строки с этим словом уберёт
     * {@link #onUserDictionaryChanged}.
     *
     * @param project проект результата — «проектный словарь» в диалоге означает его словарь
     */
    static void addToDictionary(IProject project, String word)
    {
        ComfortSpellingEngine.setCheckProjectOverride(project);
        try
        {
            ComfortSpellingEngine.addUserWordFromUi(word);
        }
        finally
        {
            ComfortSpellingEngine.setCheckProjectOverride(null);
        }
    }

    /**
     * Заменяет слово с ошибкой в строках результата: в свойствах модели — записью в модель, в
     * модулях — правкой текста (открытый редактор остаётся несохранённым, закрытый файл
     * сохраняется). Перед заменой каждое вхождение сверяется с текущим текстом: если текст с
     * момента проверки изменился и слова на месте нет, строка пропускается.
     *
     * <p>После записи тексты затронутых объектов проверяются заново — замена сдвигает смещения
     * остальных слов того же текста, а внутренние номера объектов модель после правки может
     * сменить.
     */
    static void replaceWord(CompareSearchResult result, List<CompareSearchMatch> rows, String replacement)
    {
        IProject project = result.getProject();
        if (project == null || rows.isEmpty() || replacement == null || replacement.isEmpty())
            return;
        new Job("Исправление орфографии")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                Global.tempLog(LOG_TOPIC, "replace start rows=" + rows.size() + " to=" + replacement);
                try
                {
                    IBmModelManager manager = Global.getOsgiService(IBmModelManager.class);
                    IBmModel model = manager != null ? manager.getModel(project) : null;
                    if (model == null)
                        throw new IllegalStateException("Не найдена модель проекта " + project.getName());
                    int[] replaced = { 0 };
                    // Свойства модели: правка текста владельца целиком, вхождения — с конца к началу.
                    Map<Long, List<CompareSearchMatch>> byObject = new HashMap<>();
                    for (CompareSearchMatch row : rows)
                        if (row.getModelMatch() != null)
                            byObject.computeIfAbsent(row.getModelMatch().getObjectId(), id -> new ArrayList<>()).add(row);
                    Set<String> topNames = new LinkedHashSet<>();
                    Set<Long> topIds = new HashSet<>();
                    if (!byObject.isEmpty())
                    {
                        var editing = model.createLocalContext("Исправление орфографии");
                        try
                        {
                            editing.execute(new AbstractBmTask<Void>("Исправление орфографии")
                            {
                                @Override
                                public Void execute(IBmTransaction transaction, IProgressMonitor taskMonitor)
                                {
                                    for (Map.Entry<Long, List<CompareSearchMatch>> entry : byObject.entrySet())
                                    {
                                        IBmObject object = transaction.getObjectById(entry.getKey().longValue());
                                        EStructuralFeature feature = entry.getValue().get(0).getModelMatch().getFeature();
                                        if (object == null || !(object.eGet(feature) instanceof String text))
                                            continue;
                                        List<CompareSearchMatch> ordered = new ArrayList<>(entry.getValue());
                                        ordered.sort(Comparator.comparingInt(
                                            (CompareSearchMatch row) -> row.getModelMatch().getTextOffset()).reversed());
                                        String changed = text;
                                        for (CompareSearchMatch row : ordered)
                                        {
                                            int offset = row.getModelMatch().getTextOffset();
                                            String word = row.getMisspelledWord();
                                            if (!changed.startsWith(word, offset))
                                                continue;
                                            changed = changed.substring(0, offset) + replacement
                                                + changed.substring(offset + word.length());
                                            replaced[0]++;
                                        }
                                        if (changed.equals(text))
                                            continue;
                                        object.eSet(feature, changed);
                                        IBmObject top = object.bmGetTopObject();
                                        topNames.add(top.bmGetFqn());
                                        topIds.add(Long.valueOf(top.bmGetId()));
                                    }
                                    return null;
                                }
                            });
                            editing.save();
                        }
                        finally
                        {
                            editing.dispose();
                        }
                    }
                    // Тексты изменённых объектов проверяются заново.
                    List<CompareSearchMatch> fresh = new ArrayList<>();
                    if (!topNames.isEmpty())
                    {
                        ComfortSpellingEngine.setCheckProjectOverride(project);
                        try
                        {
                            Thread caller = Thread.currentThread();
                            model.executeReadonlyTask(new AbstractBmTask<Void>("Проверка орфографии")
                            {
                                @Override
                                public Void execute(IBmTransaction transaction, IProgressMonitor taskMonitor)
                                {
                                    ComfortSpellingEngine.setCheckProjectOverride(project);
                                    try
                                    {
                                        ArrayDeque<EObject> pending = new ArrayDeque<>();
                                        for (String name : topNames)
                                        {
                                            EObject top = transaction.getTopObjectByFqn(name);
                                            if (top != null && !top.eIsProxy())
                                                pending.add(top);
                                        }
                                        traverse(model, pending, fresh, SubMonitor.convert(monitor));
                                    }
                                    finally
                                    {
                                        if (Thread.currentThread() != caller)
                                            ComfortSpellingEngine.setCheckProjectOverride(null);
                                    }
                                    return null;
                                }
                            });
                        }
                        finally
                        {
                            ComfortSpellingEngine.setCheckProjectOverride(null);
                        }
                    }
                    // Модули: одна правка на файл; смещения остальных строк файла сдвигаются.
                    Map<IFile, List<CompareSearchMatch>> byFile = new LinkedHashMap<>();
                    for (CompareSearchMatch row : rows)
                        if (row.getModuleFile() != null)
                            byFile.computeIfAbsent(row.getModuleFile(), file -> new ArrayList<>()).add(row);
                    Map<IFile, List<int[]>> applied = new HashMap<>();
                    for (Map.Entry<IFile, List<CompareSearchMatch>> entry : byFile.entrySet())
                        applied.put(entry.getKey(), replaceInModule(entry.getKey(), entry.getValue(), replacement, monitor));
                    for (List<int[]> edits : applied.values())
                        replaced[0] += edits.size();

                    Set<CompareSearchMatch> selected = Collections.newSetFromMap(new IdentityHashMap<>());
                    selected.addAll(rows);
                    Display.getDefault().asyncExec(() ->
                    {
                        List<CompareSearchMatch> all = result.getMatches();
                        // Прежние строки изменённых объектов; новые берутся только для текстов,
                        // где ошибки уже были: замена не создаёт ошибок в других текстах, а объект
                        // целиком мог не входить в проверявшееся поддерево.
                        Set<String> texts = new HashSet<>();
                        all.removeIf(row ->
                        {
                            if (row.getModelMatch() == null
                                || !topIds.contains(Long.valueOf(row.getModelMatch().getTopObjectId())))
                                return false;
                            texts.add(row.getObjectPath() + '\n' + row.getPropertyName());
                            return true;
                        });
                        for (CompareSearchMatch row : fresh)
                            if (texts.contains(row.getObjectPath() + '\n' + row.getPropertyName()))
                                all.add(row);
                        for (Map.Entry<IFile, List<int[]>> entry : applied.entrySet())
                        {
                            List<int[]> edits = entry.getValue();
                            all.removeIf(row -> row.getModuleFile() != null && selected.contains(row)
                                && edits.stream().anyMatch(edit -> edit[0] == row.getModuleOffset()));
                            for (CompareSearchMatch row : all)
                                if (entry.getKey().equals(row.getModuleFile()))
                                {
                                    int shift = 0;
                                    for (int[] edit : edits)
                                        if (edit[0] < row.getModuleOffset())
                                            shift += edit[1];
                                    row.shiftModuleOffset(shift);
                                }
                        }
                        all.sort(Comparator.comparing(CompareSearchMatch::getObjectPath, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(CompareSearchMatch::getPropertyName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(CompareSearchMatch::getMatchText, String.CASE_INSENSITIVE_ORDER));
                        CompareSearchResultPage.refreshResult(result);
                        if (replaced[0] < rows.size())
                            ToastNotification.show(TITLE, "Исправлено " + replaced[0] + " из " + rows.size()
                                + ": в остальных строках текст изменился после проверки.", 10_000);
                    });
                    Global.tempLog(LOG_TOPIC, "replace finished replaced=" + replaced[0] + " tops=" + topNames
                        + " files=" + byFile.size() + " fresh=" + fresh.size());
                    return Status.OK_STATUS;
                }
                catch (Exception error)
                {
                    Global.tempLog(LOG_TOPIC, "replace failed " + error);
                    return new Status(IStatus.ERROR, "tormozit.comfort", error.getMessage(), error);
                }
            }
        }.schedule();
    }

    /**
     * Заменяет слово в модуле правкой текста через буфер файла — тем же путём, что рефакторинги:
     * открытый редактор получает правку и остаётся несохранённым, закрытый файл сохраняется.
     *
     * @return применённые правки {смещение до правки, изменение длины}
     */
    private static List<int[]> replaceInModule(IFile file, List<CompareSearchMatch> rows, String replacement,
        IProgressMonitor monitor) throws CoreException
    {
        TextFileChange change = new TextFileChange("Исправление орфографии", file);
        change.setSaveMode(TextFileChange.KEEP_SAVE_STATE);
        String text = change.getCurrentContent(monitor);
        MultiTextEdit edits = new MultiTextEdit();
        List<int[]> applied = new ArrayList<>();
        Set<Integer> offsets = new HashSet<>();
        for (CompareSearchMatch row : rows)
        {
            String word = row.getMisspelledWord();
            int offset = row.getModuleOffset();
            if (!text.startsWith(word, offset) || !offsets.add(Integer.valueOf(offset)))
                continue;
            edits.addChild(new ReplaceEdit(offset, word.length(), replacement));
            applied.add(new int[] { offset, replacement.length() - word.length() });
        }
        if (applied.isEmpty())
            return applied;
        change.setEdit(edits);
        // PerformChangeOperation сразу зовёт isValid(), а состояние проверки само не готовит —
        // без этого вызова «TextFileChange has not been initialized».
        change.initializeValidationData(monitor);
        ResourcesPlugin.getWorkspace().run(new PerformChangeOperation(change), monitor);
        return applied;
    }

    /** Результаты проверки, ещё живые в панели «Поиск» (история поиска держит их сама). */
    private static final Set<CompareSearchResult> RESULTS = Collections.newSetFromMap(new WeakHashMap<>());

    /** Открывает редактор объекта узла дерева результата. */
    static void openObject(IProject project, String bmFqn)
    {
        IWorkbenchPage page = Global.getActivePage();
        String fullName = MdTypeMapping.bmFqnToRuFullName(bmFqn);
        EObject object = page != null
            ? GoToDefinition.resolveEObjectForFullName(fullName != null ? fullName : bmFqn, page, project) : null;
        if (object == null)
            return;
        try
        {
            new OpenHelper(page).openEditor(object);
        }
        catch (Exception error)
        {
            Global.tempLog(LOG_TOPIC, "open object failed fqn=" + bmFqn + " error=" + error);
        }
    }

    private static void schedule(IProject project, List<URI> roots, String scope)
    {
        if (project == null)
            return;
        new Job(JOB_NAME)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                long started = System.currentTimeMillis();
                Global.tempLog(LOG_TOPIC, "start project=" + project.getName() + " roots=" + roots);
                // Проектный словарь: активный проект из фонового потока не определяется.
                ComfortSpellingEngine.setCheckProjectOverride(project);
                try
                {
                    if (ComfortSpellingEngine.sharedDictionaries().isEmpty())
                    {
                        toast("Словари орфографии не загружены — см. Параметры → Общие → Орфография.");
                        return Status.OK_STATUS;
                    }
                    IBmModelManager manager = Global.getOsgiService(IBmModelManager.class);
                    IBmModel model = manager != null ? manager.getModel(project) : null;
                    if (model == null)
                        throw new IllegalStateException("Не найдена модель проекта " + project.getName());
                    SubMonitor progress = SubMonitor.convert(monitor, 100);
                    List<CompareSearchMatch> matches = new ArrayList<>();
                    Set<IContainer> moduleFolders = new LinkedHashSet<>();
                    Thread caller = Thread.currentThread();
                    model.executeReadonlyTask(new AbstractBmTask<Void>(JOB_NAME)
                    {
                        @Override
                        public Void execute(IBmTransaction transaction, IProgressMonitor taskMonitor)
                        {
                            ComfortSpellingEngine.setCheckProjectOverride(project);
                            try
                            {
                                checkModel(model, transaction, roots, matches, moduleFolders, progress.split(70));
                            }
                            finally
                            {
                                // Чужой поток задач модели не должен унести проект в свои проверки.
                                if (Thread.currentThread() != caller)
                                    ComfortSpellingEngine.setCheckProjectOverride(null);
                            }
                            return null;
                        }
                    });
                    int inModel = matches.size();
                    if (roots == null)
                        moduleFolders.add(project);
                    List<IFile> modules = moduleFiles(moduleFolders);
                    checkModules(modules, matches, progress.split(30));
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    matches.sort(Comparator.comparing(CompareSearchMatch::getObjectPath, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CompareSearchMatch::getPropertyName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CompareSearchMatch::getMatchText, String.CASE_INSENSITIVE_ORDER));
                    Global.tempLog(LOG_TOPIC, "finished project=" + project.getName() + " model=" + inModel
                        + " modules=" + modules.size() + " inModules=" + (matches.size() - inModel)
                        + " ms=" + (System.currentTimeMillis() - started));
                    if (matches.isEmpty())
                    {
                        toast("Ошибок орфографии не найдено.");
                        return Status.OK_STATUS;
                    }
                    Display.getDefault().asyncExec(() ->
                    {
                        CompareSearchResult result = new CompareSearchResult(matches, null, project);
                        RESULTS.add(result);
                        result.setSpelling(true);
                        result.setScopeLabel(scope);
                        CompareSearchQuery query = new CompareSearchQuery();
                        result.setQuery(query);
                        query.setSearchResult(result);
                        NewSearchUI.runQueryInBackground(query);
                    });
                    return Status.OK_STATUS;
                }
                catch (OperationCanceledException canceled)
                {
                    return Status.CANCEL_STATUS;
                }
                catch (Exception error)
                {
                    Global.tempLog(LOG_TOPIC, "failed " + error);
                    return new Status(IStatus.ERROR, "tormozit.comfort", error.getMessage(), error);
                }
                finally
                {
                    ComfortSpellingEngine.setCheckProjectOverride(null);
                }
            }
        }.schedule();
    }

    private static void toast(String message)
    {
        Display.getDefault().asyncExec(() -> ToastNotification.show(TITLE, message, 10_000));
    }

    private static void checkModel(IBmModel model, IBmTransaction transaction, List<URI> roots,
        List<CompareSearchMatch> matches, Set<IContainer> moduleFolders, SubMonitor monitor)
    {
        ArrayDeque<EObject> pending = new ArrayDeque<>();
        if (roots == null)
        {
            Iterator<? extends EObject> top = transaction.getTopObjectIterator();
            while (top.hasNext())
            {
                monitor.checkCanceled();
                pending.add(top.next());
            }
        }
        else
        {
            IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
            for (URI uri : roots)
            {
                monitor.checkCanceled();
                EObject object = transaction.getObjectByUri(uri);
                if (object == null || object.eIsProxy())
                    continue;
                pending.add(object);
                IContainer folder = moduleFolder(lookup, object);
                if (folder != null)
                    moduleFolders.add(folder);
            }
        }
        traverse(model, pending, matches, monitor);
    }

    /** Обходит объекты очереди с вложенными и собирает ошибки их текстов. */
    private static void traverse(IBmModel model, ArrayDeque<EObject> pending, List<CompareSearchMatch> matches,
        SubMonitor monitor)
    {
        Set<EObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> ids = new HashSet<>();
        while (!pending.isEmpty())
        {
            monitor.checkCanceled();
            EObject object = pending.remove();
            monitor.setWorkRemaining(Math.max(1, pending.size() + 1));
            monitor.worked(1);
            if (!visited.add(object) || object instanceof IBmObject bm && !ids.add(bm.bmGetId()))
                continue;
            try
            {
                checkTexts(model, object, matches);
            }
            catch (RuntimeException error)
            {
                // Один объект с нечитаемым свойством не должен срывать проверку всего проекта.
                Global.tempLog(LOG_TOPIC, "object failed uri=" + EcoreUtil.getURI(object) + " error=" + error);
            }
            // Тело формы и макета — внешние самостоятельные BM-объекты, а не containment владельца.
            if (object instanceof BasicForm form)
                enqueue(form.getForm(), pending);
            if (object instanceof BasicTemplate template)
                enqueue(template.getTemplate(), pending);
            for (EReference containment : object.eClass().getEAllContainments())
            {
                if (containment.isDerived() || containment.isTransient() || containment.isVolatile())
                    continue;
                Object value = object.eGet(containment, true);
                if (value instanceof EObject child)
                    enqueue(child, pending);
                else if (value instanceof List<?> children)
                    for (Object child : children)
                        if (child instanceof EObject nested)
                            enqueue(nested, pending);
            }
        }
    }

    /** Вложенный объект без данных в модели (форма без файла Form.form) пропускается. */
    private static void enqueue(EObject child, ArrayDeque<EObject> pending)
    {
        if (child != null && !child.eIsProxy())
            pending.add(child);
    }

    /**
     * Ошибки орфографии текста без слов CamelCase (заглавная буква после строчной): в естественном
     * языке таких слов нет — это имена (идентификаторы, названия продуктов), а не опечатки.
     */
    private static List<int[]> misspelledRanges(String text)
    {
        List<int[]> ranges = new ArrayList<>(ComfortSpellingEngine.findMisspelledRanges(text));
        ranges.removeIf(range -> isCamelCase(text, range[0], range[1]));
        return ranges;
    }

    /**
     * Проверка орфографии режет слитное имя на части («ДатаЗаема» → «Заема»), поэтому смотрим не на
     * саму часть, а на всё слитное слово вокруг неё.
     */
    private static boolean isCamelCase(String text, int offset, int length)
    {
        int start = Math.max(0, Math.min(offset, text.length()));
        int end = Math.max(start, Math.min(offset + length, text.length()));
        while (start > 0 && isNameChar(text.charAt(start - 1)))
            start--;
        while (end < text.length() && isNameChar(text.charAt(end)))
            end++;
        return hasInnerCapital(text, start, end - start);
    }

    private static boolean isNameChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean hasInnerCapital(String text, int offset, int length)
    {
        for (int i = offset + 1; i < offset + length && i < text.length(); i++)
            if (Character.isUpperCase(text.charAt(i)) && Character.isLowerCase(text.charAt(i - 1)))
                return true;
        return false;
    }

    private static void checkTexts(IBmModel model, EObject object, List<CompareSearchMatch> matches)
    {
        List<EAttribute> attributes = TEXT_ATTRIBUTES.computeIfAbsent(object.eClass(),
            // Ключ записи многоязычной строки — код языка, а не текст.
            type -> type.getEAllAttributes().stream().filter(attribute -> !attribute.isMany()
                && !attribute.isDerived() && isUserText(attribute) && !"key".equals(attribute.getName())).toList());
        for (EAttribute attribute : attributes)
        {
            if (!(object.eGet(attribute) instanceof String text) || text.isBlank())
                continue;
            // Форматированные строки хранят разметку (<b>, <a href=…>) прямо в значении свойства.
            List<int[]> ranges = misspelledRanges(ComfortSpellingEngine.maskMarkup(text));
            if (ranges.isEmpty())
                continue;
            // Свойство объекта — многоязычная строка целиком; сама запись языка своего поля не имеет.
            boolean entry = object.eClass() == McorePackage.Literals.LOCAL_STRING_MAP_ENTRY
                && object.eContainer() != null;
            EObject holder = entry ? object.eContainer() : object;
            EStructuralFeature feature = entry ? object.eContainingFeature() : attribute;
            EObject top = holder;
            while (top != null && !(top instanceof MdObject))
                top = top.eContainer();
            if (top == null)
                top = EcoreUtil.getRootContainer(object);
            String path = MdReferenceSupport.localized(EcoreUtil.getURI(top));
            String property = ConfigSearchResultsHook.metadataPropertyPath(object, attribute, top);
            if (property == null || property.isBlank())
                property = feature.getName();
            for (int[] range : ranges)
            {
                // То же вхождение, каким его записал бы поиск по конфигурации (TextModelSearcher.search):
                // объект — владелец реквизита, по нему строка открывается теми же переходами.
                TextSearchModelMatch found = object instanceof IBmObject bm ? new TextSearchModelMatch(model, text,
                    bm.bmGetTopObject().bmGetId(), bm.bmGetId(), attribute, range[0], range[1]) : null;
                matches.add(CompareSearchMatch.misspelledProperty(found, path, property,
                    fragment(text, range[0], range[1]), text.substring(range[0], range[0] + range[1])));
            }
        }
    }

    /**
     * Папка модулей выбранного объекта: у объекта верхнего уровня — его собственная папка со всеми
     * вложенными модулями, у формы и команды объекта — их папка внутри папки владельца. У прочих
     * вложенных объектов (реквизит, табличная часть) модулей нет.
     */
    private static IContainer moduleFolder(IResourceLookup lookup, EObject object)
    {
        if (lookup == null)
            return null;
        IFile descriptor;
        try
        {
            descriptor = lookup.getPlatformResource(object);
        }
        catch (RuntimeException error)
        {
            return null;
        }
        IContainer folder = descriptor != null ? descriptor.getParent() : null;
        if (folder == null || object instanceof IBmObject bm && bm.bmIsTop())
            return folder;
        if (object instanceof BasicForm form)
            return folder.getFolder(new Path("Forms").append(form.getName())); //$NON-NLS-1$
        if (object instanceof BasicCommand command)
            return folder.getFolder(new Path("Commands").append(command.getName())); //$NON-NLS-1$
        return null;
    }

    private static List<IFile> moduleFiles(Set<IContainer> folders)
    {
        List<IFile> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IContainer folder : folders)
        {
            if (!folder.isAccessible())
                continue;
            try
            {
                folder.accept((IResource resource) ->
                {
                    if (resource instanceof IFile file && BSL_EXTENSION.equalsIgnoreCase(file.getFileExtension())
                        && seen.add(file.getFullPath().toString()))
                        files.add(file);
                    return true;
                });
            }
            catch (CoreException error)
            {
                Global.tempLog(LOG_TOPIC, "module folder failed folder=" + folder.getFullPath() + " error=" + error);
            }
        }
        return files;
    }

    private static void checkModules(List<IFile> files, List<CompareSearchMatch> matches, SubMonitor monitor)
    {
        monitor.setWorkRemaining(Math.max(1, files.size()));
        for (IFile file : files)
        {
            monitor.checkCanceled();
            monitor.worked(1);
            String content = BslModuleMethodResolver.moduleText(file);
            if (content == null)
                continue;
            List<int[]> errors = new ArrayList<>();
            Matcher call = NSTR_CALL.matcher(content);
            while (call.find())
            {
                int openQuote = call.end() - 1;
                if (!ConfigSearchDialogHook.isInsideNStrLiteral(content, openQuote + 1))
                    continue;
                for (int[] range : misspelledRanges(
                    ComfortSpellingEngine.maskMarkup(literalText(content, openQuote))))
                    errors.add(new int[] { openQuote + 1 + range[0], range[1] });
            }
            if (errors.isEmpty())
                continue;
            int[] lineStarts = lineStarts(content);
            Set<Integer> lines = new LinkedHashSet<>();
            for (int[] error : errors)
                lines.add(lineOf(lineStarts, error[0]));
            Map<Integer, String> methods = BslModuleMethodResolver.methodsAtLines(file, lines);
            String relative = file.getProjectRelativePath().toString();
            String path = GetRef.resolveFullNameOrNull(relative);
            if (path == null || path.isBlank())
                path = relative;
            // Редактор отбрасывает метку порядка байтов в начале файла, чтение файла — нет.
            int shift = content.startsWith("﻿") ? 1 : 0; //$NON-NLS-1$
            for (int[] error : errors)
            {
                int line = lineOf(lineStarts, error[0]);
                int lineStart = lineStarts[line - 1];
                int lineEnd = line < lineStarts.length ? lineStarts[line] : content.length();
                String method = methods != null ? methods.get(line) : null;
                matches.add(CompareSearchMatch.misspelledInModule(file, error[0] - shift, path,
                    method != null ? method : "", //$NON-NLS-1$
                    fragment(content.substring(lineStart, lineEnd), error[0] - lineStart, error[1]),
                    content.substring(error[0], error[0] + error[1])));
            }
        }
    }

    /**
     * Текст литерала от открывающей кавычки до закрывающей той же длины, что и в модуле: отступы и
     * {@code |} строк продолжения, строки-комментарии между ними и {@code ""} заменены пробелами,
     * поэтому смещения слов совпадают со смещениями в модуле. Правила строк продолжения — как у
     * поиска по конфигурации.
     */
    private static String literalText(String content, int openQuote)
    {
        int length = content.length();
        StringBuilder text = new StringBuilder();
        int pos = openQuote + 1;
        while (pos < length)
        {
            char c = content.charAt(pos);
            if (c == '"')
            {
                if (pos + 1 >= length || content.charAt(pos + 1) != '"')
                    break;
                text.append("  "); //$NON-NLS-1$
                pos += 2;
                continue;
            }
            if (c == '\n')
            {
                int next = continuationStart(content, pos + 1);
                if (next < 0)
                    break;
                for (int i = pos; i < next; i++)
                    text.append(' ');
                pos = next;
                continue;
            }
            text.append(c == '\r' ? ' ' : c);
            pos++;
        }
        return text.toString();
    }

    /** Смещение текста строки продолжения литерала (после {@code |}) либо {@code -1}. */
    private static int continuationStart(String content, int lineStart)
    {
        int length = content.length();
        int pos = lineStart;
        while (true)
        {
            while (pos < length && (content.charAt(pos) == ' ' || content.charAt(pos) == '\t'))
                pos++;
            if (pos < length && content.charAt(pos) == '|')
                return pos + 1;
            if (pos + 1 >= length || content.charAt(pos) != '/' || content.charAt(pos + 1) != '/')
                return -1;
            int eol = content.indexOf('\n', pos);
            if (eol < 0)
                return -1;
            pos = eol + 1;
        }
    }

    private static int[] lineStarts(String content)
    {
        int[] starts = new int[64];
        int count = 1;
        for (int pos = content.indexOf('\n'); pos >= 0; pos = content.indexOf('\n', pos + 1))
        {
            if (count == starts.length)
                starts = Arrays.copyOf(starts, count * 2);
            starts[count++] = pos + 1;
        }
        return Arrays.copyOf(starts, count);
    }

    /** Номер строки с единицы. */
    private static int lineOf(int[] lineStarts, int offset)
    {
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    /** Текст одной строкой; длинный — фрагментом вокруг слова с ошибкой. */
    private static String fragment(String text, int offset, int length)
    {
        String flat = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        if (flat.length() <= FRAGMENT_LIMIT)
            return flat.strip();
        int from = Math.max(0, offset - FRAGMENT_CONTEXT);
        int to = Math.min(flat.length(), offset + length + FRAGMENT_CONTEXT);
        return (from > 0 ? "…" : "") + flat.substring(from, to).strip() + (to < flat.length() ? "…" : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private UserTextSpellCheck() {}
}
