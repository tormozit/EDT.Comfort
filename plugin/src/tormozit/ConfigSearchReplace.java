package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextEditChangeGroup;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.ltk.core.refactoring.participants.ResourceChangeChecker;
import org.eclipse.ltk.ui.refactoring.RefactoringWizard;
import org.eclipse.ltk.ui.refactoring.RefactoringWizardOpenOperation;
import org.eclipse.ltk.ui.refactoring.UserInputWizardPage;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEditGroup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentChange;
import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentCompositeChange;
import com._1c.g5.v8.dt.search.core.Match;
import com._1c.g5.v8.dt.search.core.text.TextSearchFileMatch;
import com._1c.g5.v8.dt.search.core.text.TextSearchModelMatch;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Замена по результатам поиска в конфигурации (issue #800) — как «Заменить…» поиска по файлам:
 * поле «Заменить на» и штатный предпросмотр рефакторинга, где лишние вхождения можно снять.
 *
 * <p>Изменения — те же, что строит сама EDT для полнотекстовых вхождений при переименовании
 * ({@code TextSearchRefactoringParticipant}): вхождение в свойстве модели —
 * {@link BmObjectTextContentChange} внутри {@link BmObjectTextContentCompositeChange} на пару
 * «объект + свойство», вхождение в файле — {@link TextFileChange} через буфер файла (открытый
 * редактор получает правку и остаётся несохранённым). Объекты, которые EDT не даёт править
 * ({@code IModelEditingSupport.canEdit}), и вхождения, текст которых изменился после поиска,
 * пропускаются с предупреждением.
 */
public final class ConfigSearchReplace
{
    private static final String TAG = "ConfigSearchReplace"; //$NON-NLS-1$
    private static final String SEARCH_RESULT_CLASS = "com._1c.g5.v8.dt.internal.search.ui.SearchResult"; //$NON-NLS-1$
    private static final String SEARCH_QUERY_CLASS = "com._1c.g5.v8.dt.internal.search.ui.SearchQuery"; //$NON-NLS-1$
    private static final String TEXT_INPUT_CLASS = "com._1c.g5.v8.dt.internal.search.ui.text.TextSearchInput"; //$NON-NLS-1$
    private static final String SETTINGS_SECTION = "ConfigSearchReplace"; //$NON-NLS-1$
    private static final String KEY_REPLACE_WITH = "replaceWith"; //$NON-NLS-1$
    private static final int HISTORY_SIZE = 10;
    private static final int LABEL_MAX_LENGTH = 80;
    /** Мастер замены — для табличного режима предпросмотра ({@code RefactoringPreviewHook}). */
    static final String WIZARD_CLASS_NAME = ReplaceWizard.class.getName();

    private ConfigSearchReplace()
    {
    }

    /** Результат текстового поиска в конфигурации (не «Найти ссылки» и не чужой поиск). */
    static boolean isTextSearchResult(Object searchResult)
    {
        if (searchResult == null || !SEARCH_RESULT_CLASS.equals(searchResult.getClass().getName()))
            return false;
        Object input = searchInput(searchResult);
        return input != null && TEXT_INPUT_CLASS.equals(input.getClass().getName());
    }

    /** Все видимые (не скрытые штатными фильтрами) текстовые вхождения результата. */
    static List<Match> resultMatches(Object searchResult)
    {
        Set<Match> matches = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Match> ordered = new ArrayList<>();
        if (!(Global.invoke(searchResult, "getElements") instanceof Iterable<?> elements)) //$NON-NLS-1$
            return ordered;
        for (Object element : elements)
        {
            if (!(Global.invoke(searchResult, "getMatches", element) instanceof Iterable<?> occurrences)) //$NON-NLS-1$
                continue;
            for (Object occurrence : occurrences)
                if (isReplaceable(occurrence) && !((Match) occurrence).isFiltered() && matches.add((Match) occurrence))
                    ordered.add((Match) occurrence);
        }
        return ordered;
    }

    /** Вхождение, которое можно заменить: в файле или в текстовом свойстве модели. */
    static boolean isReplaceable(Object match)
    {
        return match instanceof TextSearchFileMatch || match instanceof TextSearchModelMatch;
    }

    /**
     * Текст заменённого вхождения после замены: строка файла или значение свойства и место
     * вставленного текста в нём — для колонки «Текст».
     */
    record ReplacedText(String text, int offset, int length)
    {
    }

    /**
     * Заменённые вхождения по результатам поиска. Из результата не удаляются: удаление пересобирает
     * узлы дерева, и строки пропадают из таблицы; строка помечена «Заменено», в счётчиках не
     * учитывается, повторно не заменяется: её смещения уже не актуальны. Ключ — сам
     * объект {@code Match}, не его {@code equals}: новый поиск находит «равное» вхождение на том же месте.
     */
    private static final Map<Object, Map<Match, ReplacedText>> REPLACED = new WeakHashMap<>();

    /** Новый текст заменённого вхождения, {@code null} — вхождение не заменялось. */
    static ReplacedText replacedText(Object searchResult, Object match)
    {
        synchronized (REPLACED)
        {
            Map<Match, ReplacedText> replaced = searchResult != null ? REPLACED.get(searchResult) : null;
            return replaced != null && match != null ? replaced.get(match) : null;
        }
    }

    /** Вхождение заменено — в любом результате: объект {@code Match} принадлежит одному результату. */
    static boolean isReplaced(Object match)
    {
        if (match == null)
            return false;
        synchronized (REPLACED)
        {
            for (Map<Match, ReplacedText> replaced : REPLACED.values())
                if (replaced.containsKey(match))
                    return true;
        }
        return false;
    }

    /** Число заменённых вхождений результата — вычитается из числа в заголовке панели «Поиск». */
    static int replacedCount(Object searchResult)
    {
        synchronized (REPLACED)
        {
            Map<Match, ReplacedText> replaced = searchResult != null ? REPLACED.get(searchResult) : null;
            return replaced != null ? replaced.size() : 0;
        }
    }

    /** В результате есть заменённые вхождения — нужна колонка «Заменено». */
    static boolean hasReplaced(Object searchResult)
    {
        synchronized (REPLACED)
        {
            Map<Match, ReplacedText> replaced = searchResult != null ? REPLACED.get(searchResult) : null;
            return replaced != null && !replaced.isEmpty();
        }
    }

    private static void setReplaced(Object searchResult, Map<Match, ReplacedText> texts, boolean replaced)
    {
        synchronized (REPLACED)
        {
            Map<Match, ReplacedText> marks = REPLACED.computeIfAbsent(searchResult, result -> new IdentityHashMap<>());
            if (replaced)
                marks.putAll(texts);
            else
                marks.keySet().removeAll(texts.keySet());
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (!display.isDisposed())
            display.asyncExec(() -> ConfigSearchResultsHook.onMatchesReplaced(searchResult));
    }

    /**
     * Открыть мастер замены.
     *
     * @param all {@code true} — заменяются все вхождения результата, иначе выделенные
     */
    static void open(Shell shell, Object searchResult, Collection<Match> matches, boolean all)
    {
        Object input = searchInput(searchResult);
        String searchString = input != null && Global.invoke(input, "getSearchString") instanceof String s ? s : ""; //$NON-NLS-1$ //$NON-NLS-2$
        List<Match> replaceable = new ArrayList<>();
        int alreadyReplaced = 0;
        for (Match match : matches)
            if (isReplaceable(match))
            {
                if (replacedText(searchResult, match) != null)
                    alreadyReplaced++;
                else
                    replaceable.add(match);
            }
        String title = all ? "Заменить все вхождения" : "Заменить выделенные вхождения";
        if (replaceable.isEmpty())
        {
            MessageDialog.openInformation(shell, Global.withPluginWindowTitle(title), alreadyReplaced > 0
                ? "Вхождения уже заменены. Чтобы заменить снова, повторите поиск." : "Нет вхождений для замены.");
            return;
        }
        ReplaceRefactoring refactoring = new ReplaceRefactoring(searchResult, replaceable, searchString);
        ReplaceWizard wizard = new ReplaceWizard(refactoring);
        wizard.setDefaultPageTitle(title);
        wizard.setWindowTitle(Global.withPluginWindowTitle("Заменить"));
        try
        {
            new RefactoringWizardOpenOperation(wizard).run(shell, "Заменить");
        }
        catch (InterruptedException e)
        {
            // отмена мастера
        }
    }

    /**
     * Кнопка «Заменить…» окна «Поиск»: запустить поиск штатной страницей (со всеми её доработками
     * плагина) и после его окончания открыть мастер замены на всех найденных вхождениях.
     *
     * @return {@code true}, если поиск запущен и окно поиска нужно закрыть
     */
    static boolean searchAndReplace(Object page)
    {
        QueryCatcher catcher = new QueryCatcher();
        NewSearchUI.addQueryListener(catcher);
        boolean started = Boolean.TRUE.equals(Global.invoke(page, "performAction")); //$NON-NLS-1$
        if (!started || catcher.query == null)
        {
            NewSearchUI.removeQueryListener(catcher);
            log("searchAndReplace: поиск не запущен, started=" + started); //$NON-NLS-1$
        }
        return started;
    }

    /** Ловит запрос, добавленный нажатием «Заменить…», и открывает замену после его окончания. */
    private static final class QueryCatcher implements IQueryListener
    {
        volatile ISearchQuery query;

        @Override
        public void queryAdded(ISearchQuery added)
        {
            // runQueryInBackground зовёт queryAdded синхронно, до старта задания поиска
            if (query == null && added != null && SEARCH_QUERY_CLASS.equals(added.getClass().getName()))
                query = added;
        }

        @Override
        public void queryRemoved(ISearchQuery removed)
        {
            if (removed != null && removed == query)
                NewSearchUI.removeQueryListener(this);
        }

        @Override
        public void queryStarting(ISearchQuery starting)
        {
        }

        @Override
        public void queryFinished(ISearchQuery finished)
        {
            if (finished == null || finished != query)
                return;
            NewSearchUI.removeQueryListener(this);
            Display display = PlatformUI.getWorkbench().getDisplay();
            display.asyncExec(() ->
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                Shell shell = window != null ? window.getShell() : display.getActiveShell();
                Object result = finished.getSearchResult();
                open(shell, result, resultMatches(result), true);
            });
        }
    }

    private static Object searchInput(Object searchResult)
    {
        Object query = Global.invoke(searchResult, "getQuery"); //$NON-NLS-1$
        return query != null ? Global.getField(query, "searchInput") : null; //$NON-NLS-1$
    }

    private static void log(String message)
    {
        Global.log(TAG, message);
    }

    /** Строка вхождения для подписи правки: без переводов строк, не длиннее {@link #LABEL_MAX_LENGTH}. */
    private static String excerpt(String text, int offset, int length)
    {
        if (text == null || offset < 0 || offset > text.length())
            return ""; //$NON-NLS-1$
        int start = Math.max(text.lastIndexOf('\n', offset - 1) + 1, 0);
        int end = text.indexOf('\n', Math.min(offset + Math.max(length, 0), text.length()));
        if (end < 0)
            end = text.length();
        String line = text.substring(start, end).strip();
        return line.length() > LABEL_MAX_LENGTH ? line.substring(0, LABEL_MAX_LENGTH) + "…" : line; //$NON-NLS-1$
    }

    private static final class ReplaceRefactoring extends Refactoring
    {
        private final Object searchResult;
        private final List<Match> matches;
        private final String searchString;
        private String replaceString;
        private Change change;

        ReplaceRefactoring(Object searchResult, List<Match> matches, String searchString)
        {
            this.searchResult = searchResult;
            this.matches = matches;
            this.searchString = searchString;
        }

        @Override
        public String getName()
        {
            return "Замена по результатам поиска в конфигурации";
        }

        String getSearchString()
        {
            return searchString;
        }

        void setReplaceString(String replaceString)
        {
            this.replaceString = replaceString;
        }

        int matchCount()
        {
            return matches.size();
        }

        /** Объекты метаданных (и файлы без объекта-владельца), где лежат вхождения. */
        int ownerCount()
        {
            Set<Object> owners = new HashSet<>();
            for (Match match : matches)
            {
                long top = match.getMetadataTopObjectId();
                owners.add(top >= 0 ? Long.valueOf(top)
                    : match instanceof TextSearchFileMatch fileMatch ? fileMatch.getFile() : match);
            }
            return owners.size();
        }

        @Override
        public RefactoringStatus checkInitialConditions(IProgressMonitor pm)
        {
            return matches.isEmpty() ? RefactoringStatus.createFatalErrorStatus("Нет вхождений для замены.")
                : new RefactoringStatus();
        }

        @Override
        public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException
        {
            if (replaceString == null)
                return RefactoringStatus.createFatalErrorStatus("Не задан текст замены.");
            Map<IFile, List<TextSearchFileMatch>> byFile = new LinkedHashMap<>();
            Map<ModelKey, List<TextSearchModelMatch>> byObject = new LinkedHashMap<>();
            for (Match match : matches)
            {
                if (match instanceof TextSearchFileMatch fileMatch && fileMatch.getFile() != null)
                    byFile.computeIfAbsent(fileMatch.getFile(), file -> new ArrayList<>()).add(fileMatch);
                else if (match instanceof TextSearchModelMatch modelMatch)
                    byObject.computeIfAbsent(new ModelKey(modelMatch), key -> new ArrayList<>()).add(modelMatch);
            }
            SubMonitor progress = SubMonitor.convert(pm, "Подготовка замены", byFile.size() + byObject.size() + 1);
            RefactoringStatus status = new RefactoringStatus();
            IModelEditingSupport editing = editingSupport();
            CompositeChange root = new CompositeChange(
                "Заменить «" + searchString + "» на «" + replaceString + "»"); //$NON-NLS-1$ //$NON-NLS-2$
            root.markAsSynthetic();
            List<ReplacedMatch> replaced = new ArrayList<>();
            int[] stale = new int[1];

            List<IFile> files = new ArrayList<>(byFile.keySet());
            files.sort(Comparator.comparing(file -> file.getFullPath().toString(), String.CASE_INSENSITIVE_ORDER));
            checkFiles(files, status);
            if (status.hasFatalError())
                return status;
            progress.worked(1);
            for (IFile file : files)
            {
                progress.checkCanceled();
                if (editing != null && !editing.canEdit(fileProxy(file), EditingMode.DIRECT))
                {
                    status.addWarning("Файл недоступен для изменения, вхождения пропущены: " + file.getFullPath());
                    progress.worked(1);
                    continue;
                }
                TextFileChange fileChange = createFileChange(file, byFile.get(file), replaced, stale, progress.split(1));
                if (fileChange != null)
                    root.add(fileChange);
            }
            for (Map.Entry<ModelKey, List<TextSearchModelMatch>> entry : byObject.entrySet())
            {
                progress.checkCanceled();
                Change objectChange = createObjectChange(entry.getValue(), editing, status, replaced, stale,
                    progress.split(1));
                if (objectChange != null)
                    root.add(objectChange);
            }
            if (stale[0] > 0)
                status.addWarning("Текст изменился после поиска, вхождения пропущены: " + stale[0]
                    + ". Чтобы заменить их, повторите поиск.");
            if (replaced.isEmpty())
            {
                status.addFatalError("Нечего заменять.");
                return status;
            }
            root.add(new ResultUpdateChange(searchResult, replaced, replaceString, null, true));
            change = root;
            return status;
        }

        private static void checkFiles(List<IFile> files, RefactoringStatus status) throws CoreException
        {
            List<IFile> readOnly = new ArrayList<>();
            for (IFile file : files)
                if (file.isReadOnly())
                    readOnly.add(file);
            if (!readOnly.isEmpty())
            {
                IStatus edit = ResourcesPlugin.getWorkspace().validateEdit(readOnly.toArray(new IFile[0]), null);
                if (edit.getSeverity() == IStatus.CANCEL)
                    throw new OperationCanceledException();
                status.merge(RefactoringStatus.create(edit));
                if (status.hasFatalError())
                    return;
            }
            status.merge(ResourceChangeChecker.checkFilesToBeChanged(files.toArray(new IFile[0]), null));
        }

        private TextFileChange createFileChange(IFile file, List<TextSearchFileMatch> fileMatches,
            List<ReplacedMatch> replaced, int[] stale, IProgressMonitor monitor) throws CoreException
        {
            TextFileChange fileChange = new TextFileChange(fileChangeName(file, fileMatches.get(0)), file);
            fileChange.setSaveMode(TextFileChange.KEEP_SAVE_STATE);
            MultiTextEdit root = new MultiTextEdit();
            fileChange.setEdit(root);
            String content = fileChange.getCurrentContent(monitor);
            List<TextSearchFileMatch> ordered = new ArrayList<>(fileMatches);
            ordered.sort(Comparator.comparingInt(TextSearchFileMatch::getFileOffset));
            for (TextSearchFileMatch match : ordered)
            {
                String line = match.getText();
                int inLine = match.getTextOffset();
                int length = match.getTextLength();
                int offset = match.getFileOffset();
                if (line == null || inLine < 0 || length <= 0 || inLine + length > line.length() || offset < 0
                    || !content.regionMatches(offset, line, inLine, length))
                {
                    stale[0]++;
                    continue;
                }
                ReplaceEdit edit = new ReplaceEdit(offset, length, replaceString);
                try
                {
                    root.addChild(edit);
                }
                catch (MalformedTreeException overlap)
                {
                    // вхождения по маске могут пересекаться — второе уже покрыто первым
                    continue;
                }
                TextEditChangeGroup group = new TextEditChangeGroup(fileChange, new TextEditGroup(
                    "Строка " + match.getLineNumber() + ": " + excerpt(line, inLine, length), edit)); //$NON-NLS-1$ //$NON-NLS-2$
                fileChange.addTextEditChangeGroup(group);
                replaced.add(new ReplacedMatch(match, () -> fileChange.isEnabled() && group.isEnabled()));
            }
            return root.hasChildren() ? fileChange : null;
        }

        private Change createObjectChange(List<TextSearchModelMatch> objectMatches, IModelEditingSupport editing,
            RefactoringStatus status, List<ReplacedMatch> replaced, int[] stale, IProgressMonitor monitor)
            throws CoreException
        {
            TextSearchModelMatch first = objectMatches.get(0);
            String name = changeName(first);
            EStructuralFeature feature = first.getFeature();
            if (!(feature instanceof EAttribute) || feature.isMany()
                || feature.getEType() == null || feature.getEType().getInstanceClass() != String.class)
            {
                status.addWarning("Свойство не текстовое, вхождения пропущены: " + name);
                return null;
            }
            IBmObject object = first.resolveMatchObject().orElse(null);
            if (object == null)
            {
                stale[0] += objectMatches.size();
                return null;
            }
            if (editing != null && !editing.canEdit(object, EditingMode.DIRECT))
            {
                status.addWarning("Объект недоступен для изменения, вхождения пропущены: " + name);
                return null;
            }
            BmObjectTextContentCompositeChange<EObject> objectChange =
                new BmObjectTextContentCompositeChange<>(name, object, feature);
            String content = null;
            List<TextSearchModelMatch> ordered = new ArrayList<>(objectMatches);
            ordered.sort(Comparator.comparingInt(TextSearchModelMatch::getTextOffset));
            int previousEnd = -1;
            for (TextSearchModelMatch match : ordered)
            {
                String text = match.getText();
                int offset = match.getTextOffset();
                int length = match.getTextLength();
                BmObjectTextContentChange<EObject> matchChange = new BmObjectTextContentChange<>(
                    excerpt(text, offset, length), object, feature, new ReplaceEdit(offset, length, replaceString));
                if (content == null)
                    content = matchChange.getCurrentContent(monitor);
                if (text == null || offset < 0 || length <= 0 || offset + length > text.length() || content == null
                    || !content.regionMatches(offset, text, offset, length))
                {
                    stale[0]++;
                    continue;
                }
                if (offset < previousEnd)
                    continue; // пересечение вхождений по маске
                previousEnd = offset + length;
                objectChange.add(matchChange);
                replaced.add(new ReplacedMatch(match, () -> objectChange.isEnabled() && matchChange.isEnabled()));
            }
            return objectChange.getChildren().length > 0 ? objectChange : null;
        }

        @Override
        public Change createChange(IProgressMonitor pm)
        {
            return change;
        }

        private static String fileChangeName(IFile file, TextSearchFileMatch match)
        {
            String path = ConfigSearchResultsHook.matchDisplayPath(match);
            return path != null ? path : file.getFullPath().toString();
        }

        private static String changeName(TextSearchModelMatch match)
        {
            String path = ConfigSearchResultsHook.matchDisplayPath(match);
            return path != null ? path : String.valueOf(match.getFeature().getName());
        }

        private static IModelEditingSupport editingSupport()
        {
            try
            {
                return ServiceAccess.get(IModelEditingSupport.class);
            }
            catch (RuntimeException e)
            {
                log("IModelEditingSupport недоступен: " + e); //$NON-NLS-1$
                return null;
            }
        }

        /** Как проверка файла в {@code BslTextSearchRefactoringSupplier}: прокси корня ресурса файла. */
        private static EObject fileProxy(IFile file)
        {
            EObject proxy = EcoreFactory.eINSTANCE.createEObject();
            ((InternalEObject) proxy).eSetProxyURI(
                URI.createPlatformResourceURI(file.getFullPath().toString(), true).appendFragment("/0")); //$NON-NLS-1$
            return proxy;
        }
    }

    /** Пара «объект + свойство» — одно изменение модели на все вхождения в этом тексте. */
    private record ModelKey(long objectId, EStructuralFeature feature)
    {
        ModelKey(TextSearchModelMatch match)
        {
            this(match.getObjectId(), match.getFeature());
        }

        @Override
        public boolean equals(Object other)
        {
            return other instanceof ModelKey key && key.objectId == objectId && key.feature == feature;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(Long.valueOf(objectId), Integer.valueOf(System.identityHashCode(feature)));
        }
    }

    /** Строка файла ({@code TextSearchFileMatch}) или значение свойства ({@code TextSearchModelMatch}). */
    private static String textOf(Match match)
    {
        return match instanceof TextSearchFileMatch fileMatch ? fileMatch.getText()
            : ((TextSearchModelMatch) match).getText();
    }

    private static int textOffset(Match match)
    {
        return match instanceof TextSearchFileMatch fileMatch ? fileMatch.getTextOffset()
            : ((TextSearchModelMatch) match).getTextOffset();
    }

    private static int textLength(Match match)
    {
        return match instanceof TextSearchFileMatch fileMatch ? fileMatch.getTextLength()
            : ((TextSearchModelMatch) match).getTextLength();
    }

    /** Вхождение и признак, что его правка осталась отмеченной в предпросмотре. */
    private record ReplacedMatch(Match match, BooleanSupplier enabled)
    {
    }

    /**
     * Помечает заменённые вхождения результата поиска «Заменено» и запоминает их новый текст
     * (как {@code SearchResultUpdateChange} поиска по файлам, но вхождения остаются в списке);
     * отмена снимает пометку.
     */
    private static final class ResultUpdateChange extends Change
    {
        private final Object searchResult;
        /** Кандидаты до выполнения: пометку получат только оставшиеся отмеченными. */
        private final List<ReplacedMatch> candidates;
        private final String replacement;
        /** Вхождения уже выполненной правки и их новый текст — для отмены и повтора. */
        private final Map<Match, ReplacedText> applied;
        private final boolean mark;

        ResultUpdateChange(Object searchResult, List<ReplacedMatch> candidates, String replacement,
            Map<Match, ReplacedText> applied, boolean mark)
        {
            this.searchResult = searchResult;
            this.candidates = candidates;
            this.replacement = replacement;
            this.applied = applied;
            this.mark = mark;
        }

        @Override
        public String getName()
        {
            return "Обновить результаты поиска";
        }

        @Override
        public void initializeValidationData(IProgressMonitor pm)
        {
        }

        @Override
        public RefactoringStatus isValid(IProgressMonitor pm)
        {
            return new RefactoringStatus();
        }

        @Override
        public Change perform(IProgressMonitor pm)
        {
            Map<Match, ReplacedText> target = applied;
            if (target == null)
            {
                List<Match> enabled = new ArrayList<>();
                for (ReplacedMatch candidate : candidates)
                    if (candidate.enabled().getAsBoolean())
                        enabled.add(candidate.match());
                target = replacedTexts(enabled, replacement);
            }
            setReplaced(searchResult, target, mark);
            return new ResultUpdateChange(searchResult, List.of(), replacement, target, !mark);
        }

        /**
         * Новый текст каждого вхождения: все замены одной строки файла (одного значения свойства)
         * применяются вместе — в каждой строке таблицы виден итоговый текст.
         */
        private static Map<Match, ReplacedText> replacedTexts(List<Match> matches, String replacement)
        {
            Map<Object, List<Match>> groups = new LinkedHashMap<>();
            for (Match match : matches)
            {
                Object key = match instanceof TextSearchFileMatch fileMatch
                    ? List.of(fileMatch.getFile(), Long.valueOf(fileMatch.getLineNumber()))
                    : new ModelKey((TextSearchModelMatch) match);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(match);
            }
            Map<Match, ReplacedText> texts = new IdentityHashMap<>();
            for (List<Match> group : groups.values())
            {
                group.sort(Comparator.comparingInt(ConfigSearchReplace::textOffset));
                String old = textOf(group.get(0));
                if (old == null)
                    continue;
                StringBuilder changed = new StringBuilder();
                Map<Match, Integer> starts = new IdentityHashMap<>();
                int position = 0;
                for (Match match : group)
                {
                    int offset = textOffset(match);
                    int end = offset + textLength(match);
                    if (offset < position || end > old.length())
                        continue;
                    changed.append(old, position, offset);
                    starts.put(match, Integer.valueOf(changed.length()));
                    changed.append(replacement);
                    position = end;
                }
                changed.append(old, position, old.length());
                String text = changed.toString();
                for (Map.Entry<Match, Integer> start : starts.entrySet())
                    texts.put(start.getKey(), new ReplacedText(text, start.getValue().intValue(), replacement.length()));
            }
            return texts;
        }

        @Override
        public Object getModifiedElement()
        {
            return null;
        }
    }

    private static final class ReplaceWizard extends RefactoringWizard
    {
        ReplaceWizard(ReplaceRefactoring refactoring)
        {
            super(refactoring, DIALOG_BASED_USER_INTERFACE);
        }

        @Override
        protected void addUserInputPages()
        {
            addPage(new ReplaceInputPage((ReplaceRefactoring) getRefactoring()));
        }
    }

    /** Поле «Заменить на» с историей, как {@code ReplaceConfigurationPage} поиска по файлам. */
    private static final class ReplaceInputPage extends UserInputWizardPage
    {
        private final ReplaceRefactoring refactoring;
        private Combo replaceField;

        ReplaceInputPage(ReplaceRefactoring refactoring)
        {
            super("ConfigSearchReplaceInputPage"); //$NON-NLS-1$
            this.refactoring = refactoring;
        }

        @Override
        public void createControl(Composite parent)
        {
            Composite result = new Composite(parent, SWT.NONE);
            result.setLayout(new GridLayout(2, false));

            int count = refactoring.matchCount();
            int owners = refactoring.ownerCount();
            Label description = new Label(result, SWT.NONE);
            description.setText("Будет заменено " + count + " " + Global.russianPlural(count, "вхождение", "вхождения", "вхождений") //$NON-NLS-1$ //$NON-NLS-2$
                + " в " + owners + " " + Global.russianPlural(owners, "объекте", "объектах", "объектах") + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            description.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));

            new Label(result, SWT.NONE).setText("Найти:");
            Text search = new Text(result, SWT.BORDER | SWT.READ_ONLY);
            search.setText(refactoring.getSearchString());
            GridData searchData = new GridData(GridData.FILL_HORIZONTAL);
            searchData.widthHint = convertWidthInCharsToPixels(50);
            search.setLayoutData(searchData);

            new Label(result, SWT.NONE).setText("Заменить на:");
            replaceField = new Combo(result, SWT.DROP_DOWN);
            GridData replaceData = new GridData(GridData.FILL_HORIZONTAL);
            replaceData.widthHint = convertWidthInCharsToPixels(50);
            replaceField.setLayoutData(replaceData);
            String[] history = settings().getArray(KEY_REPLACE_WITH);
            if (history != null && history.length > 0)
            {
                replaceField.setItems(history);
                replaceField.select(0);
            }
            replaceField.setFocus();

            setControl(result);
            Dialog.applyDialogFont(result);
        }

        @Override
        protected boolean performFinish()
        {
            initializeRefactoring();
            return super.performFinish();
        }

        @Override
        public org.eclipse.jface.wizard.IWizardPage getNextPage()
        {
            initializeRefactoring();
            return super.getNextPage();
        }

        private void initializeRefactoring()
        {
            String text = replaceField.getText();
            refactoring.setReplaceString(text);
            List<String> history = new ArrayList<>();
            history.add(text);
            for (String item : replaceField.getItems())
                if (history.size() < HISTORY_SIZE && !history.contains(item))
                    history.add(item);
            settings().put(KEY_REPLACE_WITH, history.toArray(new String[0]));
        }

        private static IDialogSettings settings()
        {
            IDialogSettings top = Activator.getDefault().getDialogSettings();
            IDialogSettings section = top.getSection(SETTINGS_SECTION);
            return section != null ? section : top.addNewSection(SETTINGS_SECTION);
        }
    }
}
