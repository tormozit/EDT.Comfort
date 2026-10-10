package tormozit;

import java.nio.file.Path;
import java.io.InputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.egit.core.RepositoryCache;
import org.eclipse.egit.core.RepositoryUtil;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCache;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCacheEntry;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffData;
import org.eclipse.egit.ui.UIUtils;
import org.eclipse.egit.ui.internal.dialogs.CommitDialog;
import org.eclipse.egit.ui.internal.operations.DeletePathsOperationUI;
import org.eclipse.egit.ui.internal.staging.StagingEntry;
import org.eclipse.egit.ui.internal.staging.StagingView;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.core.runtime.IPath;
import java.util.Collection;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.modeling.xml.XmlResource;
import com._1c.g5.modeling.xml.parser.XmlParserAdapter;
import com._1c.g5.modeling.xml.parser.IXmlParser;
import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.compare.datasource.DataSourceType;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceFactory;
import com._1c.g5.v8.dt.compare.datasource.IProjectSourceProvider;
import com._1c.g5.v8.dt.core.platform.ProjectManifest;
import com._1c.g5.v8.dt.platform.version.Version;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.naming.ISymbolicNameService;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com._1c.g5.v8.dt.core.platform.IVirtualProjectResourceImportService;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.library.repository.ILibraryRepository;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.naming.QualifiedName;

/**
 * Доработки панели «Индексирование Git»: выбор репозитория и проверка состава индекса перед фиксацией.
 * Поля currentRepository, commitButton, commitAndPushButton подтверждены в StagingView EGit.
 * Собственный источник читает blob непосредственно из индекса/HEAD, без записи в Git и копирования файлов.
 * Без удаления штатный импорт EDT получает только файлы коммита и цели их сохранённых ссылок.
 * Предыдущая ревизия импортируется только для изменённых объектов. Весь индекс обходится при удалении.
 * Модель рабочего проекта используется исключительно для адресов переходов из панели «Поиск».
 * Фильтр файлов и его настройка живут отдельно в GitStagingFilterHook.
 * Та же проверка подключается к окну EGit «Фиксировать изменения» ({@link CommitDialog}): там в коммит
 * идут помеченные файлы рабочего каталога поверх HEAD, поэтому состав собирается в памяти.
 * Операции панели над рабочим каталогом (замена на HEAD-ревизию, удаление файла) проверяются так же:
 * проверяемый состав — рабочий каталог после операции, см. {@link #checkWorkTreeOperation}.
 */
public final class GitStagingViewHook implements IStartup
{
    private static final String VIEW_ID = "com._1c.g5.v8.dt.internal.team.ui.views.DtStagingView";
    private static final String BUTTON_KEY = "tormozit.gitStaging.commitCheck";
    private static final String TITLE = "Проверка ссылок коммита";
    private static final String LOG = "broken-links-commit";
    private static final Set<IViewPart> pending = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public void earlyStartup()
    {
        Global.tempLog(LOG, "startup entered");
        Display.getDefault().asyncExec(() ->
        {
            Global.tempLog(LOG, "startup UI entered");
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
            // Окно фиксации модальное и создаётся заново при каждом вызове: кнопки готовы к SWT.Show.
            Display.getDefault().addFilter(SWT.Show, event ->
            {
                if (event.widget instanceof Shell shell && shell.getData() instanceof CommitDialog dialog)
                    patchDialog(dialog, shell);
            });
            Global.tempLog(LOG, "startup installed");
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        for (var page : window.getPages())
            for (IViewReference ref : page.getViewReferences())
                schedule(ref.getView(false));
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { check(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { check(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { check(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}

            private void check(IWorkbenchPartReference ref)
            {
                if (ref.getPart(false) instanceof IViewPart view)
                    schedule(view);
            }
        });
    }

    private static void schedule(IViewPart view)
    {
        if (view == null || view.getSite() == null || !VIEW_ID.equals(view.getSite().getId())
            || !pending.add(view))
            return;
        patch(view, 0);
    }

    private static void patch(IViewPart view, int attempt)
    {
        Object first = Global.getField(view, "commitButton");
        Object second = Global.getField(view, "commitAndPushButton");
        if (first instanceof Button commit && second instanceof Button push)
        {
            pending.remove(view);
            if (commit.isDisposed() || push.isDisposed())
                return;
            RepositorySelection.install(view, commit);
            DeleteCheckAction.install(view);
            CommitButtons session = new CommitButtons(new StagingSource(view), commit, push);
            session.install(commit);
            session.install(push);
            return;
        }
        if (attempt >= 120)
        {
            pending.remove(view);
            Global.tempLog(LOG, "buttons unavailable view=" + view.getClass().getName());
            return;
        }
        Display.getDefault().timerExec(attempt < 30 ? 100 : 500, () -> patch(view, attempt + 1));
    }

    /** Поля commitButton, commitAndPushButton, filesViewer, repository подтверждены в CommitDialog EGit 6.8. */
    private static void patchDialog(CommitDialog dialog, Shell shell)
    {
        try
        {
            if (Global.getField(dialog, "commitButton") instanceof Button commit
                && Global.getField(dialog, "commitAndPushButton") instanceof Button push
                && !commit.isDisposed() && !push.isDisposed())
            {
                CommitButtons session = new CommitButtons(new DialogSource(dialog, shell), commit, push);
                session.install(commit);
                session.install(push);
            }
            else
                Global.tempLog(LOG, "dialog buttons unavailable dialog=" + dialog.getClass().getName());
        }
        catch (Throwable error) { Global.tempLogException(LOG, "dialog hook failed", error); }
    }

    private static record ProjectCheck(IProject project, URI navigationBase) {}
    private static record Findings(IProject project, List<CompareSearchMatch> matches) {}

    /**
     * Состав будущего коммита на момент нажатия кнопки. {@code workTree} — содержимое файлов,
     * которых в Git ещё нет (окно фиксации берёт их из рабочего каталога), по идентификатору blob.
     * Для операции над рабочим каталогом: {@code index} — каталог после неё, {@code previous} — до неё
     * (иначе {@code null}: исходное состояние — HEAD), {@code changed} — файлы, которые она меняет
     * (иначе {@code null}: отличия индекса от HEAD).
     * <p>
     * Для проверки достижимости файлов: {@code appearing} — файлы, которые действие создаёт (иначе
     * {@code null}: файлы индекса, которых нет в HEAD), {@code loose} — файлы рабочего каталога,
     * не попавшие в {@code index} и {@code previous}: содержимое остальных файлов проверка ссылок
     * не читает, но для достижимости важно само их наличие.
     */
    private static record Snapshot(DirCache index, ObjectId head, Map<ObjectId, byte[]> workTree,
        DirCache previous, Set<String> changed, Set<String> appearing, Set<String> loose) {}

    /** Слова проверяемого действия в сообщениях проверки. */
    private static record Wording(String title, String scope, String question, String cancel, String proceeds,
        String canceled, String changed)
    {
        static final Wording COMMIT = new Wording(TITLE, "составе коммита",
            "Коммит содержит битые ссылки на метаданные (показаны в панели Поиск). Продолжить?",
            "Отменить фиксацию", "Продолжается штатная фиксация.", "Фиксация отменена.",
            "Состав коммита изменился во время проверки");
        private static final String OPERATION_QUESTION =
            "Операция приведет к появлению битых ссылок на метаданные (показаны в панели Поиск). Продолжить?";
        static final Wording REPLACE = new Wording("Проверка ссылок замены на HEAD-ревизию",
            "результате замены на HEAD-ревизию", OPERATION_QUESTION, "Отменить замену",
            "Продолжается замена на HEAD-ревизию.", "Замена отменена.",
            "Рабочий каталог изменился во время проверки");
        static final Wording DELETE = new Wording("Проверка ссылок удаления файлов",
            "результате удаления файлов", OPERATION_QUESTION, "Отменить удаление",
            "Продолжается удаление.", "Удаление отменено.",
            "Рабочий каталог изменился во время проверки");

        /** Вопрос о недостижимых файлах метаданных, см. {@link MdReachability}. */
        String unreachableQuestion()
        {
            return this == COMMIT
                ? "Коммит содержит недостижимые файлы метаданных (показаны в панели Поиск). Продолжить?"
                : "Операция приведет к появлению недостижимых файлов метаданных (показаны в панели Поиск). Продолжить?";
        }
    }

    /** Кто запросил проверку: на её время запрещает повторный запуск, после неё выполняет действие. */
    private interface Requester
    {
        void lock();

        void unlock();

        /** Выполнять действие уже негде: результат не показывается. */
        boolean gone();

        void proceed();
    }

    /**
     * Источник проверяемого состава: панель «Индексирование Git», окно «Фиксировать изменения»
     * или операция над рабочим каталогом.
     */
    private interface CheckSource
    {
        default Wording wording() { return Wording.COMMIT; }

        Shell shell();

        /** {@code null} — проверять нечего, выполняется штатная обработка кнопки. */
        Repository repository();

        boolean commitPossible(Repository repository) throws Exception;

        boolean saveEditors(Repository repository);

        /** Вызывается в потоке интерфейса; возвращённое чтение выполняется в фоне. */
        java.util.concurrent.Callable<Snapshot> reader(Repository repository);

        boolean unchanged(Repository repository, Snapshot snapshot) throws IOException;
    }

    private static final class StagingSource implements CheckSource
    {
        private final IViewPart view;

        StagingSource(IViewPart view) { this.view = view; }

        @Override public Shell shell() { return view.getSite().getShell(); }

        @Override
        public Repository repository()
        {
            return Global.getField(view, "currentRepository") instanceof Repository repository ? repository : null;
        }

        @Override
        public boolean commitPossible(Repository repository) throws Exception
        {
            Class<?> stateClass = Class.forName("org.eclipse.egit.ui.internal.staging.LazyRepositoryState",
                false, view.getClass().getClassLoader());
            var constructor = stateClass.getDeclaredConstructor(Repository.class);
            constructor.setAccessible(true);
            Object possible = Global.invoke(view, "isCommitPossible", constructor.newInstance(repository));
            Global.tempLog(LOG, "native isCommitPossible=" + possible);
            if (!(possible instanceof Boolean))
                throw new IllegalStateException("Не удалось определить режим кнопки фиксации");
            return Boolean.TRUE.equals(possible);
        }

        @Override
        public boolean saveEditors(Repository repository)
        {
            return UIUtils.saveAllEditors(repository, "Фиксация отменена после сохранения редакторов");
        }

        @Override
        public java.util.concurrent.Callable<Snapshot> reader(Repository repository)
        {
            // Только чтение: фоновый импорт никогда не удерживает блокировку индекса Git.
            return () -> new Snapshot(repository.readDirCache(), repository.resolve("HEAD"), Map.of(), null, null,
                null, Set.of());
        }

        @Override
        public boolean unchanged(Repository repository, Snapshot snapshot) throws IOException
        {
            if (Global.getField(view, "currentRepository") != repository
                || !java.util.Objects.equals(snapshot.head(), repository.resolve("HEAD")))
                return false;
            DirCache index = snapshot.index();
            DirCache current = repository.readDirCache();
            if (current.getEntryCount() != index.getEntryCount())
                return false;
            for (int i = 0; i < current.getEntryCount(); i++)
            {
                var first = current.getEntry(i);
                var second = index.getEntry(i);
                if (!first.getPathString().equals(second.getPathString())
                    || first.getStage() != second.getStage()
                    || first.getRawMode() != second.getRawMode()
                    || !first.getObjectId().equals(second.getObjectId()))
                    return false;
            }
            return true;
        }
    }

    /**
     * Окно EGit «Фиксировать изменения». Штатная операция фиксирует помеченные файлы рабочего
     * каталога поверх HEAD (остальные изменения индекса в коммит не идут), поэтому состав —
     * дерево HEAD с заменой помеченных файлов их текущим содержимым, только в памяти.
     */
    private static final class DialogSource implements CheckSource
    {
        private final CommitDialog dialog;
        private final Shell shell;
        private List<String> selected = List.of();

        DialogSource(CommitDialog dialog, Shell shell)
        {
            this.dialog = dialog;
            this.shell = shell;
        }

        @Override public Shell shell() { return shell; }

        @Override
        public Repository repository()
        {
            return Global.getField(dialog, "repository") instanceof Repository repository ? repository : null;
        }

        @Override public boolean commitPossible(Repository repository) { return true; }

        // Редакторы сохраняет штатный вызов до открытия окна.
        @Override public boolean saveEditors(Repository repository) { return true; }

        @Override
        public java.util.concurrent.Callable<Snapshot> reader(Repository repository)
        {
            selected = selection();
            List<String> files = selected;
            Global.tempLog(LOG, "dialog selection files=" + files);
            return () -> snapshot(repository, files);
        }

        @Override
        public boolean unchanged(Repository repository, Snapshot snapshot) throws IOException
        {
            // Закрытое окно фиксацию уже не выполнит: кнопка уничтожена.
            return shell.isDisposed() || java.util.Objects.equals(snapshot.head(), repository.resolve("HEAD"))
                && selection().equals(selected);
        }

        /** Пометки файлов; путь — от корня рабочего каталога репозитория (CommitItem.path). */
        private List<String> selection()
        {
            if (!(Global.getField(dialog, "filesViewer") instanceof org.eclipse.jface.viewers.CheckboxTreeViewer viewer))
                throw new IllegalStateException("Недоступен список файлов окна фиксации");
            List<String> result = new ArrayList<>();
            for (Object item : viewer.getCheckedElements())
                if (Global.getField(item, "path") instanceof String path)
                    result.add(path);
            Collections.sort(result);
            return result;
        }

        private static Snapshot snapshot(Repository repository, List<String> files) throws IOException
        {
            ObjectId head = repository.resolve("HEAD");
            DirCache index = DirCache.newInCore();
            if (head != null)
            {
                DirCacheBuilder builder = index.builder();
                try (RevWalk revisions = new RevWalk(repository); var reader = repository.newObjectReader())
                {
                    builder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, revisions.parseCommit(head).getTree());
                }
                builder.finish();
            }
            Map<ObjectId, byte[]> workTree = new HashMap<>();
            Set<String> appearing = new HashSet<>();
            Set<String> loose = new HashSet<>();
            DirCacheEditor editor = index.editor();
            try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter())
            {
                for (String file : files)
                {
                    File source = new File(repository.getWorkTree(), file);
                    if (source.isFile() && index.getEntry(file) == null)
                    {
                        appearing.add(file);
                        if (!IndexSource.metadataFile(file))
                            loose.add(file);
                    }
                    // Остальные файлы проверка не читает: их содержимое остаётся как в HEAD.
                    if (!IndexSource.metadataFile(file))
                        continue;
                    if (!source.isFile())
                    {
                        editor.add(new DirCacheEditor.DeletePath(file));
                        continue;
                    }
                    byte[] content = java.nio.file.Files.readAllBytes(source.toPath());
                    ObjectId id = formatter.idFor(Constants.OBJ_BLOB, content);
                    workTree.put(id, content);
                    editor.add(new DirCacheEditor.PathEdit(file)
                    {
                        @Override
                        public void apply(DirCacheEntry entry)
                        {
                            entry.setFileMode(FileMode.REGULAR_FILE);
                            entry.setObjectId(id);
                        }
                    });
                }
            }
            editor.finish();
            Global.tempLog(LOG, "dialog snapshot entries=" + index.getEntryCount() + " workTree=" + workTree.size()
                + " head=" + head);
            return new Snapshot(index, head, workTree, null, null, appearing, loose);
        }
    }

    /**
     * Операция панели над файлами рабочего каталога: замена на HEAD-ревизию или удаление. Проверяется
     * рабочий каталог после операции; исходное состояние — он же до операции, а не HEAD. Оба состава
     * собираются в памяти из индекса и неиндексированных изменений (кэш EGit), в Git ничего не пишется.
     */
    private static final class WorkTreeSource implements CheckSource, Requester
    {
        /** Проверка одна на все панели: повторный запуск операции во время неё отклоняется. */
        private static boolean running;
        private final Shell shell;
        private final Repository repository;
        private final Set<String> paths;
        private final boolean delete;
        private final Runnable operation;

        WorkTreeSource(Shell shell, Repository repository, Set<String> paths, boolean delete, Runnable operation)
        {
            this.shell = shell;
            this.repository = repository;
            this.paths = paths;
            this.delete = delete;
            this.operation = operation;
        }

        @Override public Wording wording() { return delete ? Wording.DELETE : Wording.REPLACE; }

        @Override public Shell shell() { return shell; }

        @Override public Repository repository() { return repository; }

        @Override public boolean commitPossible(Repository repository) { return true; }

        // Проверяется содержимое файлов на диске, как его увидит сама операция.
        @Override public boolean saveEditors(Repository repository) { return true; }

        @Override
        public java.util.concurrent.Callable<Snapshot> reader(Repository repository) { return this::snapshot; }

        @Override
        public boolean unchanged(Repository repository, Snapshot snapshot) throws IOException
        {
            return java.util.Objects.equals(snapshot.head(), repository.resolve("HEAD"));
        }

        @Override public void lock() { running = true; }

        @Override public void unlock() { running = false; }

        @Override public boolean gone() { return shell != null && shell.isDisposed(); }

        @Override public void proceed() { operation.run(); }

        private Snapshot snapshot() throws IOException
        {
            ObjectId head = repository.resolve("HEAD");
            IndexDiffCacheEntry cache = IndexDiffCache.INSTANCE.getIndexDiffCacheEntry(repository);
            IndexDiffData diff = cache != null ? cache.getIndexDiff() : null;
            if (diff == null)
                throw new IllegalStateException("Недоступно состояние рабочего каталога Git");
            // Прочитанный индекс правится только в памяти: без блокировки и записи.
            DirCache before = repository.readDirCache();
            if (before.hasUnmergedPaths())
                throw new IllegalStateException("В индексе Git есть неразрешённые конфликты");
            Map<ObjectId, byte[]> workTree = new HashMap<>();
            DirCacheEditor editor = before.editor();
            try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter())
            {
                for (String file : diff.getMissing())
                    if (IndexSource.metadataFile(file))
                        editor.add(new DirCacheEditor.DeletePath(file));
                for (Collection<String> files : List.of(diff.getModified(), diff.getUntracked()))
                    for (String file : files)
                    {
                        // Остальные файлы проверка не читает: их содержимое остаётся как в индексе.
                        if (!IndexSource.metadataFile(file))
                            continue;
                        File source = new File(repository.getWorkTree(), file);
                        if (!source.isFile())
                        {
                            editor.add(new DirCacheEditor.DeletePath(file));
                            continue;
                        }
                        byte[] content = java.nio.file.Files.readAllBytes(source.toPath());
                        ObjectId id = formatter.idFor(Constants.OBJ_BLOB, content);
                        workTree.put(id, content);
                        editor.add(replacement(file, id));
                    }
            }
            editor.finish();
            Set<String> loose = new HashSet<>();
            for (String file : diff.getUntracked())
                if (!IndexSource.metadataFile(file) && new File(repository.getWorkTree(), file).isFile())
                    loose.add(file);
            Set<String> appearing = new HashSet<>();
            DirCache after = DirCache.newInCore();
            DirCacheBuilder builder = after.builder();
            for (int i = 0; i < before.getEntryCount(); i++)
            {
                // Копии записей: правка итогового состава не должна менять исходный.
                DirCacheEntry entry = before.getEntry(i);
                DirCacheEntry copy = new DirCacheEntry(entry.getRawPath());
                copy.setFileMode(entry.getFileMode());
                copy.setObjectId(entry.getObjectId());
                builder.add(copy);
            }
            builder.finish();
            DirCacheEditor result = after.editor();
            if (delete)
                for (String file : paths)
                    result.add(new DirCacheEditor.DeletePath(file));
            else
                try (RevWalk revisions = new RevWalk(repository))
                {
                    var tree = head == null ? null : revisions.parseCommit(head).getTree();
                    for (String file : paths)
                        try (TreeWalk walk = tree == null ? null : TreeWalk.forPath(repository, file, tree))
                        {
                            result.add(walk == null ? new DirCacheEditor.DeletePath(file)
                                : replacement(file, walk.getObjectId(0)));
                            if (walk != null && !new File(repository.getWorkTree(), file).isFile())
                                appearing.add(file);
                        }
                }
            result.finish();
            Global.tempLog(LOG, "work tree snapshot delete=" + delete + " paths=" + paths
                + " before=" + before.getEntryCount() + " after=" + after.getEntryCount()
                + " modified=" + diff.getModified().size() + " untracked=" + diff.getUntracked().size()
                + " missing=" + diff.getMissing().size() + " read=" + workTree.size() + " head=" + head);
            return new Snapshot(after, head, workTree, before, paths, appearing, loose);
        }

        private static DirCacheEditor.PathEdit replacement(String file, ObjectId id)
        {
            return new DirCacheEditor.PathEdit(file)
            {
                @Override
                public void apply(DirCacheEntry entry)
                {
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    entry.setObjectId(id);
                }
            };
        }
    }

    /**
     * Проверяет битые ссылки, которые появятся после операции над файлами рабочего каталога, и только
     * затем выполняет её. Без файлов метаданных и при выключенном флажке
     * {@link ComfortSettings#PREF_CONTROL_MD_REFERENCES} операция выполняется сразу. Ошибка и пропуск проверки
     * операцию не отменяют — как и при фиксации.
     *
     * @param paths пути файлов от корня рабочего каталога репозитория
     * @param delete файлы удаляются; иначе их содержимое заменяется состоянием HEAD
     */
    static void checkWorkTreeOperation(Shell shell, Repository repository, Collection<String> paths,
        boolean delete, Runnable operation)
    {
        // Достижимость проверяется для любых файлов исходников конфигурации, не только метаданных.
        Set<String> files = new HashSet<>();
        int metadata = 0;
        for (String path : paths)
            if (IndexSource.metadataFile(path))
            {
                files.add(path);
                metadata++;
            }
            else if (path.startsWith("src/") || path.contains("/src/"))
                files.add(path);
        Global.tempLog(LOG, "work tree operation delete=" + delete + " paths=" + paths.size()
            + " metadata=" + metadata + " sources=" + files.size() + " repository=" + repository
            + " running=" + WorkTreeSource.running + " control=" + ComfortSettings.isControlMdReferencesEnabled());
        if (repository == null || files.isEmpty() || !ComfortSettings.isControlMdReferencesEnabled())
        {
            operation.run();
            return;
        }
        WorkTreeSource source = new WorkTreeSource(shell, repository, files, delete, operation);
        if (WorkTreeSource.running)
        {
            ToastNotification.show(source.wording().title(), "Предыдущая проверка ссылок ещё выполняется.", 5_000);
            return;
        }
        source.lock();
        CommitButtons.CheckAttempt attempt = new CommitButtons.CheckAttempt(source, source);
        try { attempt.begin(); }
        catch (Throwable error) { attempt.finish(error); }
    }

    /** Штатное удаление файлов EGit ({@code StagingView.DeleteAction}) с предварительной проверкой ссылок. */
    static void deleteWithCheck(IViewPart view, IStructuredSelection selection)
    {
        List<IPath> locations = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Repository repository = null;
        for (Object element : selection.toList())
            if (element instanceof StagingEntry entry)
            {
                locations.add(entry.getLocation());
                paths.add(entry.getPath());
                repository = entry.getRepository();
            }
        if (locations.isEmpty())
            return;
        checkWorkTreeOperation(view.getSite().getShell(), repository, paths, true,
            () -> new DeletePathsOperationUI(locations, view.getSite()).run());
    }

    /**
     * Клавиша Delete в списке «Неиндексированные изменения». Штатный обработчик
     * ({@code StagingView.GlobalDeleteActionHandler}, ставится в {@code updateToolbar}) решает только
     * доступность; удаление идёт через {@link #deleteWithCheck}.
     */
    private static final class DeleteCheckAction extends Action
    {
        private final IViewPart view;
        private final IAction original;

        private DeleteCheckAction(IViewPart view, IAction original)
        {
            this.view = view;
            this.original = original;
        }

        static void install(IViewPart view)
        {
            var bars = view.getViewSite().getActionBars();
            String id = ActionFactory.DELETE.getId();
            IAction original = bars.getGlobalActionHandler(id);
            if (original instanceof DeleteCheckAction)
                return;
            Global.tempLog(LOG, "delete handler original=" + (original == null ? null : original.getClass().getName()));
            if (original == null)
                return;
            bars.setGlobalActionHandler(id, new DeleteCheckAction(view, original));
            bars.updateActionBars();
        }

        @Override public boolean isEnabled() { return original.isEnabled(); }

        @Override
        public void run()
        {
            if (Global.getField(view, "unstagedViewer") instanceof TreeViewer viewer)
                deleteWithCheck(view, viewer.getStructuredSelection());
            else
                original.run();
        }
    }

    /** reload/currentRepository/realRepository подтверждены в StagingView и DtStagingView целевой EDT. */
    private static final class RepositorySelection
    {
        private static final String CONTROL_KEY = "tormozit.gitStaging.repositorySelection";
        private static final String SETTINGS_SECTION = "gitStaging";
        private static final String LAST_REPOSITORY = "lastRepoGitDir";
        private static final String DIAGNOSTICS = "git-staging-repository";
        private final StagingView view;
        private final Button control;
        private final Runnable observe = this::observe;
        private String remembered;

        private RepositorySelection(StagingView view, Button control)
        {
            this.view = view;
            this.control = control;
        }

        static void install(IViewPart view, Button control)
        {
            if (!(view instanceof StagingView staging) || control.getData(CONTROL_KEY) != null)
                return;
            RepositorySelection selection = new RepositorySelection(staging, control);
            control.setData(CONTROL_KEY, selection);
            control.addDisposeListener(event ->
            {
                // StagingView.dispose() очищает currentRepository раньше уничтожения контролов,
                // но оставляет realRepository: сохраняем и выбор непосредственно перед закрытием.
                selection.remember(true);
                control.getDisplay().timerExec(-1, selection.observe);
            });
            try { selection.restore(); }
            catch (Exception error) { Global.tempLogException(DIAGNOSTICS, "restore failed", error); }
            selection.observe();
        }

        private static IDialogSettings settings()
        {
            IDialogSettings root = Activator.getDefault().getDialogSettings();
            IDialogSettings section = root.getSection(SETTINGS_SECTION);
            return section != null ? section : root.addNewSection(SETTINGS_SECTION);
        }

        private void restore() throws IOException
        {
            String last = settings().get(LAST_REPOSITORY);
            List<File> directories = new ArrayList<>();
            for (String path : RepositoryUtil.INSTANCE.getConfiguredRepositories())
            {
                File directory = new File(RepositoryUtil.INSTANCE.getAbsoluteRepositoryPath(path));
                if (directory.isDirectory() && !directories.contains(directory))
                    directories.add(directory);
            }
            File selected = directories.size() == 1 ? directories.get(0) : null;
            if (selected == null && last != null)
                for (File directory : directories)
                    if (directory.equals(new File(last)))
                    {
                        selected = directory;
                        break;
                    }
            if (selected == null)
                return;
            Repository repository = RepositoryCache.INSTANCE.lookupRepository(selected);
            if (!repository.isBare() && repository.getWorkTree().isDirectory()
                && Global.getField(view, "currentRepository") != repository)
                view.reload(repository);
        }

        private void observe()
        {
            if (control.isDisposed())
                return;
            remember(false);
            // В панели нет события смены репозитория. Читаем подтверждённое поле,
            // не заменяя штатные провайдеры деревьев и обработчики меню EGit.
            control.getDisplay().timerExec(500, observe);
        }

        private void remember(boolean closing)
        {
            try
            {
                Object current = Global.getField(view, "currentRepository");
                if (closing && current == null)
                    current = Global.getField(view, "realRepository");
                if (!(current instanceof Repository repository) || repository.getDirectory() == null)
                    return;
                String path = repository.getDirectory().getAbsolutePath();
                if (!path.equals(remembered))
                {
                    settings().put(LAST_REPOSITORY, path);
                    remembered = path;
                }
            }
            catch (Exception error) { Global.tempLogException(DIAGNOSTICS, "remember failed", error); }
        }
    }

    private static final class CommitButtons
    {
        private final CheckSource source;
        private final Button commit;
        private final Button push;
        private boolean running;

        CommitButtons(CheckSource source, Button commit, Button push)
        {
            this.source = source;
            this.commit = commit;
            this.push = push;
        }

        void install(Button button)
        {
            if (button.getData(BUTTON_KEY) != null)
                return;
            Listener[] original = button.getListeners(SWT.Selection);
            if (original.length == 0)
                return;
            button.setData(BUTTON_KEY, this);
            for (Listener listener : original)
                button.removeListener(SWT.Selection, listener);
            button.addListener(SWT.Selection, event -> selected(button, event, original));
            Global.tempLog(LOG, "button hooked text=" + button.getText() + " listeners=" + original.length);
        }

        private void selected(Button button, Event event, Listener[] original)
        {
            Global.tempLog(LOG, "selection button=" + button.getText() + " running=" + running
                + " control=" + ComfortSettings.isControlMdReferencesEnabled());
            if (running)
                return;
            if (!ComfortSettings.isControlMdReferencesEnabled())
            {
                for (Listener listener : original)
                    listener.handleEvent(event);
                return;
            }
            running = true;
            boolean commitEnabled = commit.getEnabled();
            boolean pushEnabled = push.getEnabled();
            CheckAttempt attempt = new CheckAttempt(source, new Requester()
            {
                @Override
                public void lock()
                {
                    commit.setEnabled(false);
                    push.setEnabled(false);
                }

                @Override
                public void unlock()
                {
                    try { if (!commit.isDisposed()) commit.setEnabled(commitEnabled); }
                    catch (Throwable ignored) { /* Штатная операция должна продолжиться. */ }
                    try { if (!push.isDisposed()) push.setEnabled(pushEnabled); }
                    catch (Throwable ignored) { /* Штатная операция должна продолжиться. */ }
                    running = false;
                }

                @Override public boolean gone() { return button.isDisposed(); }

                @Override
                public void proceed()
                {
                    for (Listener listener : original)
                        listener.handleEvent(event);
                }
            });
            try { attempt.begin(); }
            catch (Throwable error) { attempt.finish(error); }
        }

        private static void reportSkipped(String title, String message, Throwable error)
        {
            try
            {
                if (error == null)
                    Global.tempLog(LOG, message);
                else
                    Global.tempLogException(LOG, message, error);
            }
            catch (Throwable loggingError) { /* Диагностика не влияет на фиксацию. */ }
            try
            {
                Display.getDefault().asyncExec(() ->
                {
                    try { ToastNotification.show(title, message); }
                    catch (Throwable notificationError) { /* Уведомление не влияет на фиксацию. */ }
                });
            }
            catch (Throwable notificationError) { /* Уведомление не влияет на фиксацию. */ }
        }

        /** Одна проверка перед действием: фиксацией или операцией над рабочим каталогом. */
        private static final class CheckAttempt
        {
            private final CheckSource source;
            private final Requester requester;
            private final Wording words;
            private final List<Findings> findings = Collections.synchronizedList(new ArrayList<>());
            private final List<UnreachableFilesSearchResult> unreachable = Collections.synchronizedList(new ArrayList<>());
            private final IProgressMonitor cancellation = new NullProgressMonitor();
            private final Runnable askCancellation = this::askCancellation;
            private boolean askingCancellation;
            private boolean commitCanceled;
            private Runnable deferredFinish;
            private Repository repository;
            private volatile Snapshot snapshot;
            private long started;
            private boolean finished;

            CheckAttempt(CheckSource source, Requester requester)
            {
                this.source = source;
                this.requester = requester;
                this.words = source.wording();
            }

            private void skipped(String message, Throwable error)
            {
                reportSkipped(words.title(), message, error);
            }

            void begin() throws Exception
            {
                Repository selected = source.repository();
                Global.tempLog(LOG, "begin source=" + source.getClass().getSimpleName() + " repository=" + selected);
                if (selected == null)
                {
                    Display.getDefault().asyncExec(() -> finish(null));
                    return;
                }
                repository = selected;
                if (!source.commitPossible(repository))
                {
                    Display.getDefault().asyncExec(() -> finish(null));
                    return;
                }
                if (!source.saveEditors(repository))
                {
                    finished = true;
                    requester.unlock();
                    return;
                }
                java.util.concurrent.Callable<Snapshot> reader = source.reader(repository);
                started = System.nanoTime();
                requester.lock();
                // Один вопрос на любую проверку, частичную и полную: ограничения по времени нет.
                Display.getDefault().timerExec(ASK_AFTER_MS, askCancellation);
                new Job(words.title())
                {
                    @Override protected IStatus run(IProgressMonitor monitor)
                    {
                        Throwable failure = null;
                        try
                        {
                            Snapshot content = reader.call();
                            snapshot = content;
                            DirCache index = content.index();
                            ObjectId head = content.head();
                            if (index.hasUnmergedPaths())
                                throw new IllegalStateException("В индексе Git есть неразрешённые конфликты");
                            List<ProjectCheck> selectedProjects = projects(repository);
                            Global.tempLog(LOG, "index read entries=" + index.getEntryCount()
                                + " projects=" + selectedProjects.size() + " head=" + head);
                            try
                            {
                                check(repository, content, selectedProjects, findings,
                                    content.changed() != null ? content.changed() : changedFiles(repository, index, head),
                                    cancellation);
                            }
                            finally
                            {
                                // Достижимость файлов не зависит от исхода проверки ссылок.
                                if (!cancellation.isCanceled())
                                    try
                                    {
                                        findUnreachable(repository, content, selectedProjects, words.scope(),
                                            unreachable, cancellation);
                                    }
                                    catch (Throwable error) { Global.logError("MdReachability", "commit check failed", error); }
                            }
                        }
                        catch (Throwable error) { failure = error; }
                        final Throwable result = failure;
                        Display.getDefault().asyncExec(() -> finish(result));
                        return Status.OK_STATUS;
                    }
                }.schedule();
            }

            private void askCancellation()
            {
                if (finished)
                    return;
                int choice = 1;
                askingCancellation = true;
                try
                {
                    showFindings();
                    choice = new MessageDialog(shell(), Global.withPluginWindowTitle(words.title()),
                        null, "Проверка битых ссылок может занять длительное время",
                        MessageDialog.QUESTION, new String[] { "Продолжить проверку", "Пропустить проверку",
                            words.cancel() }, 0).open();
                    Global.tempLog(LOG, "long check choice=" + choice);
                }
                catch (Throwable failure)
                {
                    skipped("Не удалось запросить продолжение проверки ссылок.", failure);
                }
                finally { askingCancellation = false; }
                if (choice != 0)
                {
                    // Закрытие диалога также отменяет фиксацию; ошибка показа пропускает проверку.
                    commitCanceled = choice != 1;
                    deferredFinish = null;
                    finish(new OperationCanceledException());
                }
                else if (deferredFinish != null)
                {
                    Runnable completion = deferredFinish;
                    deferredFinish = null;
                    completion.run();
                }
            }

            void finish(Throwable error)
            {
                // Оба завершения выполняются в UI: вопрос и ответ фоновой проверки конкурируют только здесь.
                if (finished)
                    return;
                if (askingCancellation)
                {
                    deferredFinish = () -> finish(error);
                    return;
                }
                finished = true;
                Global.tempLog(LOG, "finish ms=" + (started == 0 ? 0 : (System.nanoTime() - started) / 1_000_000)
                    + " error=" + error);
                cancellation.setCanceled(true);
                boolean proceed = !commitCanceled;
                try
                {
                    Display.getDefault().timerExec(-1, askCancellation);
                    // Окно фиксации закрыли во время проверки: фиксации не будет, показывать нечего.
                    if (requester.gone())
                        return;
                    if (repository != null && snapshot != null && !source.unchanged(repository, snapshot))
                        throw new IllegalStateException(words.changed());
                    int count = showFindings();
                    if (error instanceof OperationCanceledException)
                    {
                        if (!commitCanceled)
                            skipped("Проверка битых ссылок пропущена. " + words.proceeds(), null);
                    }
                    else if (error != null)
                        skipped("Проверка ссылок недоступна. " + words.proceeds(), error);
                    else if (count > 0 && !commitCanceled)
                        proceed = MessageDialog.openQuestion(shell(),
                            Global.withPluginWindowTitle(words.title()), words.question());
                    // Пропущенная проверка ничего не показывает; ошибка проверки ссылок этой не мешает.
                    if (proceed && !(error instanceof OperationCanceledException) && showUnreachable())
                        proceed = MessageDialog.openQuestion(shell(),
                            Global.withPluginWindowTitle(words.title()), words.unreachableQuestion());
                }
                catch (Throwable failure)
                {
                    skipped(commitCanceled ? words.canceled() + " Не удалось завершить проверку ссылок."
                        : "Проверка ссылок недоступна. " + words.proceeds(), failure);
                }
                finally { requester.unlock(); }
                // Исключения штатных обработчиков не перехватываются как ошибки нашей проверки.
                if (proceed && !commitCanceled && !requester.gone())
                    requester.proceed();
            }

            /** Окно фиксации может быть уже закрыто — тогда родителем служит окно приложения. */
            private Shell shell()
            {
                Shell shell = source.shell();
                if (shell != null && !shell.isDisposed())
                    return shell;
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                return window != null ? window.getShell() : null;
            }

            private boolean showUnreachable()
            {
                List<UnreachableFilesSearchResult> results;
                synchronized (unreachable) { results = new ArrayList<>(unreachable); }
                for (UnreachableFilesSearchResult result : results)
                    result.show(true);
                return !results.isEmpty();
            }

            private int showFindings()
            {
                List<Findings> snapshot;
                synchronized (findings) { snapshot = new ArrayList<>(findings); }
                int count = 0;
                for (Findings result : snapshot)
                {
                    List<CompareSearchMatch> rows;
                    synchronized (result.matches()) { rows = new ArrayList<>(result.matches()); }
                    if (rows.isEmpty())
                        continue;
                    count += rows.size();
                    CompareSearchResult search = new CompareSearchResult(rows, null, result.project());
                    search.setQueryText("битые ссылки метаданных");
                    search.setScopeLabel(words.scope());
                    CompareSearchQuery query = new CompareSearchQuery();
                    search.setQuery(query);
                    query.setSearchResult(search);
                    var status = NewSearchUI.runQueryInForeground(null, query);
                    if (status == null || !status.isOK())
                        throw new IllegalStateException("Не удалось показать битые ссылки в панели Поиск");
                }
                return count;
            }
        }

        private static List<ProjectCheck> projects(Repository repository)
        {
            IBmModelManager manager = Global.getOsgiService(IBmModelManager.class);
            if (manager == null)
                throw new IllegalStateException("Недоступна служба моделей EDT");
            List<ProjectCheck> result = new ArrayList<>();
            Path workTree = repository.getWorkTree().toPath();
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (!project.isOpen() || project.getLocation() == null
                    || !project.getLocation().toFile().toPath().startsWith(workTree))
                    continue;
                RepositoryMapping mapping = RepositoryMapping.getMapping(project);
                if (mapping == null || mapping.getRepository() == null
                    || !repository.getDirectory().equals(mapping.getRepository().getDirectory()))
                    continue;
                IBmModel model = manager.getModel(project);
                if (model == null)
                {
                    if (project.getFile("src/Configuration/Configuration.mdo").exists())
                        throw new IllegalStateException("Недоступна модель проекта " + project.getName());
                    continue;
                }
                var configuration = model.getEngine().getTopObjectByFqn("Configuration");
                if (configuration != null)
                    result.add(new ProjectCheck(project, EcoreUtil.getURI(configuration).trimFragment().trimSegments(1)));
            }
            return result;
        }

        private static Set<String> changedFiles(Repository repository, DirCache index, ObjectId head)
            throws java.io.IOException
        {
            Set<String> result = new HashSet<>();
            try (RevWalk revisions = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository))
            {
                if (head == null)
                    tree.addTree(new EmptyTreeIterator());
                else
                    tree.addTree(revisions.parseCommit(head).getTree());
                tree.addTree(new DirCacheIterator(index));
                tree.setRecursive(true);
                tree.setFilter(TreeFilter.ANY_DIFF);
                while (tree.next())
                    result.add(tree.getPathString());
            }
            return result;
        }

        /**
         * Недостижимые папки метаданных, которые образует действие ({@link MdReachability#findNew}).
         * Модель EDT не нужна: читаются только пути состава и тексты описателей.
         */
        private static void findUnreachable(Repository repository, Snapshot content, List<ProjectCheck> projects,
            String scope, List<UnreachableFilesSearchResult> results, IProgressMonitor monitor) throws IOException
        {
            DirCache index = content.index();
            DirCache before = content.previous();
            Set<String> appearing = content.appearing();
            if (appearing == null)
            {
                appearing = new HashSet<>();
                try (RevWalk revisions = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository))
                {
                    if (content.head() == null)
                        tree.addTree(new EmptyTreeIterator());
                    else
                        tree.addTree(revisions.parseCommit(content.head()).getTree());
                    tree.addTree(new DirCacheIterator(index));
                    tree.setRecursive(true);
                    tree.setFilter(TreeFilter.ANY_DIFF);
                    while (tree.next())
                        if (tree.getRawMode(0) == 0)
                            appearing.add(tree.getPathString());
                }
            }
            for (ProjectCheck project : projects)
            {
                String root = repository.getWorkTree().toPath()
                    .relativize(project.project().getLocation().toFile().toPath()).toString().replace('\\', '/');
                String prefix = root.isEmpty() ? "" : root + "/";
                String sources = prefix + "src/";
                // Удаляемые операцией файлы рабочего каталога после неё отсутствуют.
                Set<String> looseAfter = new HashSet<>(content.loose());
                if (content.changed() != null)
                    looseAfter.removeIf(file -> content.changed().contains(file) && index.getEntry(file) == null);
                List<String> files = sourceFiles(index, looseAfter, sources, prefix);
                List<String> added = new ArrayList<>();
                for (String file : appearing)
                    if (file.startsWith(sources))
                        added.add(file.substring(prefix.length()));
                IndexSource after = new IndexSource(repository, index, prefix, null, content.workTree());
                IndexSource previous = before != null
                    ? new IndexSource(repository, before, prefix, null, content.workTree())
                    : new IndexSource(repository, index, prefix, content.head(), Map.of());
                List<MdReachability.Item> items = MdReachability.findNew(project.project(), new MdReachability.Change(
                    files, before != null ? sourceFiles(before, content.loose(), sources, prefix) : null, added,
                    reachabilityFiles(previous, content.loose(), prefix),
                    reachabilityFiles(after, looseAfter, prefix)), monitor);
                // Восстанавливаемый описатель «Заменить на HEAD-ревизию» сама прописывает в Configuration.mdo.
                if (before != null)
                    items.removeIf(item -> item.mdo() != null && content.appearing().contains(prefix + item.mdo()));
                if (!items.isEmpty())
                {
                    items.sort(java.util.Comparator.comparing(MdReachability.Item::fullName, String.CASE_INSENSITIVE_ORDER));
                    UnreachableFilesSearchResult result =
                        new UnreachableFilesSearchResult(project.project(), items, scope);
                    // Только панель «Индексирование Git» фиксирует сам индекс: состав без своих
                    // списков создаваемых файлов и без исходного каталога.
                    result.setIndexed(before == null && content.appearing() == null);
                    results.add(result);
                }
            }
        }

        /** Файлы исходников проекта в составе; пути — от корня проекта. */
        private static List<String> sourceFiles(DirCache index, Set<String> loose, String sources, String prefix)
        {
            List<String> files = new ArrayList<>();
            for (int i = 0; i < index.getEntryCount(); i++)
            {
                String file = index.getEntry(i).getPathString();
                if (file.startsWith(sources))
                    files.add(file.substring(prefix.length()));
            }
            for (String file : loose)
                if (file.startsWith(sources))
                    files.add(file.substring(prefix.length()));
            return files;
        }

        private static MdReachability.Files reachabilityFiles(IndexSource source, Set<String> loose, String prefix)
        {
            return MdReachability.cached(new MdReachability.Files()
            {
                @Override
                public boolean exists(String file)
                {
                    return source.fileExists(Path.of(file)) || loose.contains(prefix + file);
                }

                @Override
                public String content(String file)
                {
                    try (InputStream stream = source.getFileStream(Path.of(file)))
                    {
                        return stream == null ? null
                            : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    catch (IOException error) { throw new UncheckedIOException(error); }
                }
            });
        }

        private static boolean existed(Repository repository, ObjectId head, String path)
            throws java.io.IOException
        {
            if (head == null)
                return false;
            try (RevWalk revisions = new RevWalk(repository);
                TreeWalk tree = TreeWalk.forPath(repository, path, revisions.parseCommit(head).getTree()))
            {
                return tree != null;
            }
        }

        private static void check(Repository repository, Snapshot content, List<ProjectCheck> projects,
            List<Findings> findings, Set<String> changedFiles, IProgressMonitor monitor)
            throws IOException
        {
            DirCache index = content.index();
            ObjectId head = content.head();
            IComparisonDataSourceFactory factory = Global.getOsgiService(IComparisonDataSourceFactory.class);
            if (factory == null)
                throw new IllegalStateException("Недоступна служба источников сравнения EDT");
            IQualifiedNameFilePathConverter converter = field(factory, "qualifiedNameFilePathConverter",
                IQualifiedNameFilePathConverter.class);
            ISymbolicNameService names = field(factory, "symbolicNameService", ISymbolicNameService.class);
            IBmModelManager manager = field(factory, "bmModelManager", IBmModelManager.class);
            SubMonitor progress = SubMonitor.convert(monitor, TITLE, projects.size());
            for (ProjectCheck project : projects)
            {
                progress.checkCanceled();
                String prefix = repository.getWorkTree().toPath()
                    .relativize(project.project().getLocation().toFile().toPath()).toString().replace('\\', '/');
                if (!prefix.isEmpty())
                    prefix += "/";
                Set<String> projectFiles = new HashSet<>();
                for (String file : changedFiles)
                    if (file.startsWith(prefix) && IndexSource.metadataSource(file.substring(prefix.length())))
                        projectFiles.add(file.substring(prefix.length()));
                if (projectFiles.isEmpty() || index.getEntry(prefix + "src/Configuration/Configuration.mdo") == null)
                {
                    progress.worked(1);
                    continue;
                }
                SubMonitor checking = progress.split(1).setWorkRemaining(100);
                checking.subTask(project.project().getName());
                IndexSource staged = new IndexSource(repository, index, prefix, null, content.workTree());
                // Исходное состояние операции над рабочим каталогом — он сам, а не HEAD.
                DirCache before = content.previous();
                IndexSource previousFiles = before != null
                    ? new IndexSource(repository, before, prefix, null, content.workTree())
                    : new IndexSource(repository, index, prefix, head, Map.of());
                boolean deleted = false;
                for (String file : projectFiles)
                {
                    checking.checkCanceled();
                    if (file.endsWith(".mdo") && !staged.fileExists(Path.of(file)))
                    {
                        deleted = true;
                        break;
                    }
                }
                staged.select(projectFiles, converter);
                IBmModel original = manager.getModel(project.project());
                if (original == null)
                    throw new IllegalStateException("Недоступна модель проекта " + project.project().getName());
                if (deleted)
                {
                    Global.tempLog(LOG, "full search started project=" + project.project().getName());
                    staged.selectAll();
                }
                else
                    staged.selectTargets(projectFiles, converter, names, original, checking.split(10));
                IComparisonDataSource source = createSource(factory, staged);
                try
                {
                    start(source, checking.split(30));
                    String configuration = prefix + "src/Configuration/Configuration.mdo";
                    if (!deleted && (before != null ? before.getEntry(configuration) != null
                        : head != null && existed(repository, head, configuration)))
                    {
                        previousFiles.select(projectFiles, converter);
                        IComparisonDataSource previous = createSource(factory, previousFiles);
                        try
                        {
                            start(previous, checking.split(25));
                            deleted = MdReferenceSupport.hasDeletedObjects(previous, source, projectFiles,
                                checking.split(5));
                        }
                        finally { stopDeferred(previous); }
                    }
                    if (deleted && !staged.full)
                    {
                        Global.tempLog(LOG, "full search started project=" + project.project().getName());
                        stopDeferred(source);
                        staged.selectAll();
                        source = createSource(factory, staged);
                        start(source, checking.split(10));
                    }
                    Global.tempLog(LOG, "scope project=" + project.project().getName() + " all=" + deleted
                        + " changed=" + projectFiles.size() + " imported=" + staged.files.size()
                        + " previous=" + previousFiles.files.size());
                    List<CompareSearchMatch> rows = Collections.synchronizedList(new ArrayList<>());
                    if (before == null)
                    {
                        findings.add(new Findings(project.project(), rows));
                        MdReferenceSupport.findInModel(source, project.navigationBase(),
                            deleted ? null : projectFiles, checking.split(20), rows::add);
                    }
                    else
                    {
                        // Операция отвечает только за ссылки, которые ломает она сама: битые и без неё
                        // отсеиваются по рабочей модели проекта, поэтому результат показывается целиком в конце.
                        List<CompareSearchMatch> found = MdReferenceSupport.findInModel(source,
                            project.navigationBase(), deleted ? null : projectFiles, checking.split(15));
                        rows.addAll(MdReferenceSupport.newInProject(project.project(), found, checking.split(5)));
                        Global.tempLog(LOG, "new links project=" + project.project().getName()
                            + " found=" + found.size() + " new=" + rows.size());
                        findings.add(new Findings(project.project(), rows));
                    }
                }
                finally { stopDeferred(source); }
                checking.done();
            }
            progress.done();
        }

        /** Задание Xtext, которое через 500 мс разбирает очередь событий индекса в редакторе модуля. */
        private static final String EDITOR_STATE_JOB =
            "org.eclipse.xtext.ui.editor.DirtyStateEditorSupport$UpdateEditorStateJob";
        private static final long STOP_WAIT_MS = 30_000;
        /** Через сколько после начала проверки спрашивать, продолжать ли её. */
        private static final int ASK_AFTER_MS = 10_000;

        /**
         * Останавливает временную модель фоновым заданием, не задерживая фиксацию. Импорт в неё
         * рассылает события индекса всем открытым редакторам модулей (штатный
         * {@code MdEObjectEmfIndexBuilder}, без отбора по модели), а редактор запрашивает адреса
         * объектов из этих событий отложенно. Остановка раньше даёт «The namespace '.virtual.N' is
         * inactive», и события остаются в очереди редактора — он падает на каждом следующем.
         * Поэтому сначала ждём конец производных расчётов модели и задания редакторов.
         */
        private static void stopDeferred(IComparisonDataSource source)
        {
            Job job = new Job("Остановка временной модели проверки коммита")
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    long started = System.nanoTime();
                    boolean computed = true;
                    int joined = 0;
                    int unfinished = 0;
                    try
                    {
                        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
                        IBmModel model = source.getBmModel();
                        var derived = provider != null && model != null ? provider.get(model) : null;
                        if (derived != null)
                            computed = derived.waitAllComputations(STOP_WAIT_MS);
                        for (Job pending : Job.getJobManager().find(null))
                        {
                            if (pending.getState() == Job.NONE || !isEditorStateJob(pending))
                                continue;
                            joined++;
                            if (!pending.join(STOP_WAIT_MS, monitor))
                                unfinished++;
                        }
                    }
                    catch (InterruptedException interrupted)
                    {
                        Thread.currentThread().interrupt();
                    }
                    catch (Exception error)
                    {
                        Global.tempLogException(LOG, "deferred stop wait failed", error);
                    }
                    finally
                    {
                        Global.tempLog(LOG, "deferred stop computed=" + computed + " editorJobs=" + joined
                            + " unfinished=" + unfinished + " ms=" + (System.nanoTime() - started) / 1_000_000);
                        source.stop();
                    }
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            job.schedule();
        }

        private static boolean isEditorStateJob(Job job)
        {
            for (Class<?> type = job.getClass(); type != null; type = type.getSuperclass())
                if (EDITOR_STATE_JOB.equals(type.getName()))
                    return true;
            return false;
        }

        private static void start(IComparisonDataSource source, IProgressMonitor monitor)
        {
            Global.tempLog(LOG, "partial import start source=" + source);
            source.startIfNecessary(monitor);
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            if (source.getBmModel() == null
                || source.getBmModel().getEngine().getTopObjectByFqn("Configuration") == null)
                throw new IllegalStateException("Не удалось прочитать объекты коммита");
        }

        /** Конструктор и поля фабрики подтверждены в ComparisonDataSourceFactory целевой EDT. */
        private static IComparisonDataSource createSource(IComparisonDataSourceFactory factory, IndexSource provider)
        {
            Global.tempLog(LOG, "source selected full=" + provider.full + " files=" + provider.files);
            try
            {
                // Внутренний пакет не экспортируется OSGi. Все его типы загружает сам бандл сравнения.
                ClassLoader loader = factory.getClass().getClassLoader();
                Class<?> providerType = Class.forName(
                    "com._1c.g5.v8.dt.internal.compare.datasource.IExtendedProjectSourceProvider", true, loader);
                Object adapter = provider.adapter(providerType);
                Class<?> sourceType = Class.forName(
                    "com._1c.g5.v8.dt.internal.compare.datasource.DtProjectDataSource", true, loader);
                Class<?>[] parameterTypes = { DataSourceType.class, providerType, ISymbolicNameService.class,
                    IQualifiedNameFilePathConverter.class, IBmModelManager.class, IWorkspaceOrchestrator.class,
                    IVirtualProjectResourceImportService.class, IDerivedDataManagerProvider.class,
                    ILibraryRepository.class };
                Object[] arguments = { DataSourceType.GIT, adapter,
                    field(factory, "symbolicNameService", ISymbolicNameService.class),
                    field(factory, "qualifiedNameFilePathConverter", IQualifiedNameFilePathConverter.class),
                    field(factory, "bmModelManager", IBmModelManager.class),
                    field(factory, "workspaceOrchestrator", IWorkspaceOrchestrator.class),
                    field(factory, "resourceImportService", IVirtualProjectResourceImportService.class),
                    field(factory, "derivedDataManagerProvider", IDerivedDataManagerProvider.class),
                    field(factory, "libraryRepository", ILibraryRepository.class) };
                return (IComparisonDataSource) constructSource(sourceType, factory, parameterTypes, arguments);
            }
            catch (ReflectiveOperationException error)
            {
                throw new IllegalStateException("Не удалось создать источник проверки коммита", error);
            }
        }

        /** Обе сигнатуры и имя поля подтверждены в бандлах сравнения 27.0.1 и 28.0.1. */
        private static Object constructSource(Class<?> sourceType, Object factory, Class<?>[] types, Object[] arguments)
            throws ReflectiveOperationException
        {
            java.lang.reflect.Constructor<?> constructor;
            try { constructor = sourceType.getConstructor(types); }
            catch (NoSuchMethodException olderSignatureAbsent)
            {
                Class<?> distribution = Class.forName("com.e1c.g5.v8.dt.distribution.IDistributionSupportManager",
                    false, sourceType.getClassLoader());
                types = java.util.Arrays.copyOf(types, types.length + 1);
                types[types.length - 1] = distribution;
                constructor = sourceType.getConstructor(types);
                arguments = java.util.Arrays.copyOf(arguments, arguments.length + 1);
                arguments[arguments.length - 1] = field(factory, "distributionSupportManager", distribution);
            }
            Global.tempLog(LOG, "source constructor=" + constructor);
            return constructor.newInstance(arguments);
        }

        private static <T> T field(Object factory, String name, Class<T> type)
        {
            Object value = Global.getField(factory, name);
            if (!type.isInstance(value))
                throw new IllegalStateException("Недоступна служба EDT: " + name);
            return type.cast(value);
        }
    }

    /** Источник только для чтения: ImportRequest задаёт ограниченный набор исходников модели. */
    private static final class IndexSource implements IProjectSourceProvider
    {
        private final Repository repository;
        private final DirCache index;
        private final String prefix;
        private final ObjectId head;
        /** Содержимое файлов рабочего каталога, которых нет в хранилище объектов Git. */
        private final Map<ObjectId, byte[]> workTree;
        private final Set<String> files = new HashSet<>();
        private final Map<String, ObjectId> oldIds = new HashMap<>();
        private boolean full;

        IndexSource(Repository repository, DirCache index, String prefix, ObjectId head,
            Map<ObjectId, byte[]> workTree)
        {
            this.repository = repository;
            this.index = index;
            this.prefix = prefix;
            this.head = head;
            this.workTree = workTree;
        }

        static boolean metadataSource(String file)
        {
            return file.startsWith("src/") && metadataFile(file);
        }

        /** Только по расширению: путь может быть задан от корня репозитория, а не проекта. */
        static boolean metadataFile(String file)
        {
            return file.endsWith(".mdo") || file.endsWith(".form") || file.endsWith(".rights")
                || file.endsWith(".dcss") || file.endsWith(".dcssca") || file.endsWith(".scheme")
                || file.endsWith(".wsdl") || file.endsWith(".xdto") || file.endsWith(".chart")
                || file.endsWith(".pnrs") || file.endsWith(".style") || file.endsWith(".cmi");
        }

        void select(Set<String> changed, IQualifiedNameFilePathConverter converter)
        {
            add("src/Configuration/Configuration.mdo");
            for (String file : changed)
            {
                add(file);
                QualifiedName fqn = converter.getFqn(file);
                if (fqn != null)
                    addObject(fqn, converter);
            }
        }

        private void addObject(QualifiedName name, IQualifiedNameFilePathConverter converter)
        {
            // Для тела формы/прав нужны также их описания и владелец. Только явные пути, без обхода каталога.
            for (QualifiedName current = name; current.getSegmentCount() > 0; current = current.skipLast(1))
                if (current.getSegmentCount() % 2 == 0 || current.equals(name)
                    || "Configuration".equals(current.toString()))
                    for (var path : converter.getAllPossibleFilePaths(current))
                        add(path.toPortableString());
        }

        private void add(String file)
        {
            if (metadataSource(file) && fileExists(Path.of(file)))
                files.add(file);
        }

        void selectAll()
        {
            if (head != null)
                throw new IllegalStateException("Полный импорт предыдущей ревизии запрещён");
            full = true;
            for (int i = 0; i < index.getEntryCount(); i++)
            {
                String file = index.getEntry(i).getPathString();
                if (file.startsWith(prefix) && metadataSource(file.substring(prefix.length())))
                    files.add(file.substring(prefix.length()));
            }
        }

        void selectTargets(Set<String> changed, IQualifiedNameFilePathConverter converter,
            ISymbolicNameService names, IBmModel original, IProgressMonitor monitor) throws IOException
        {
            for (String file : changed)
            {
                if (monitor.isCanceled())
                    throw new OperationCanceledException();
                if (!fileExists(Path.of(file)))
                    continue;
                URI uri = URI.createPlatformResourceURI("/" + getProjectName() + "/" + file, true);
                IResourceServiceProvider provider = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
                if (provider == null)
                    throw new IllegalStateException("Недоступен анализатор EDT для " + file);
                Resource.Factory factory = provider.get(Resource.Factory.class);
                XmlParserAdapter parser = provider.get(XmlParserAdapter.class);
                if (factory == null || parser == null || !(factory.createResource(uri) instanceof XMLResource nativeResource))
                    throw new IllegalStateException("Недоступен XML-анализатор EDT для " + file);
                // Только разбор XML. Ни связывания с рабочей моделью, ни разрешения ссылок её геттерами.
                XmlResource resource = new XmlResource(uri);
                Map<Object, Object> options = new HashMap<>(nativeResource.getDefaultLoadOptions());
                options.put(IXmlParser.OPTION_USE_SYMLINK, true);
                options.put("PLATFORM_VERSION", getTargetRuntimeVersion());
                parser.init(resource.createXMLHelper(), options);
                try (InputStream input = getFileStream(Path.of(file)))
                {
                    if (input == null)
                        throw new IOException("Файл исчез из индекса: " + file);
                    parser.parse(input);
                }
                if (!resource.getErrors().isEmpty() || resource.getContents().isEmpty())
                    throw new IOException("Не удалось прочитать " + file + ": " + resource.getErrors());
                QualifiedName ownerName = converter.getFqn(file);
                if (ownerName == null)
                    throw new IOException("Не удалось определить объект файла " + file);
                var objects = EcoreUtil.<EObject>getAllContents(resource.getContents(), false);
                while (objects.hasNext())
                {
                    if (monitor.isCanceled())
                        throw new OperationCanceledException();
                    EObject owner = objects.next();
                    for (EReference feature : MdReferenceSupport.features(owner.eClass()))
                    {
                        Object value = owner.eGet(feature, false);
                        if (value instanceof InternalEList<?> list)
                        {
                            var values = list.basicIterator();
                            while (values.hasNext())
                                selectTarget(values.next(), owner, feature, ownerName, converter, names, original);
                        }
                        else if (value instanceof EObject)
                            selectTarget(value, owner, feature, ownerName, converter, names, original);
                        else if (value instanceof List<?>)
                            throw new IOException("Недоступно чтение сохранённых ссылок " + file);
                    }
                }
                Global.tempLog(LOG, "parsed index file=" + prefix + file + " selected=" + files.size());
            }
        }

        private void selectTarget(Object value, EObject owner, EReference feature, QualifiedName ownerName,
            IQualifiedNameFilePathConverter converter, ISymbolicNameService names, IBmModel original)
        {
            if (!(value instanceof EObject target) || !target.eIsProxy()
                || !MdReferenceSupport.isMetadataReferenceTarget(target))
                return;
            URI saved = EcoreUtil.getURI(target);
            // OPTION_USE_SYMLINK штатного StaxXmlParser сохраняет имя в unresolved:/<имя>.
            if (!"unresolved".equals(saved.scheme()) || saved.path() == null || !saved.path().startsWith("/"))
                throw new IllegalStateException("Неизвестный формат сохранённой ссылки: " + saved);
            String name = URI.decode(saved.path().substring(1));
            URI address = names.convertSymbolicNameToUri(name, owner, feature, ownerName.toString(),
                original.getEngine(), getTargetRuntimeVersion());
            if (address != null && BmUriUtil.isBmUri(address))
                addObject(QualifiedName.create(BmUriUtil.extractTopObjectFqn(address).split("\\.")), converter);
        }

        private org.eclipse.core.resources.IProjectDescription description;
        private Version runtimeVersion;

        private synchronized org.eclipse.core.resources.IProjectDescription description()
        {
            if (description == null)
                try (InputStream stream = getFileStream(Path.of(".project")))
                {
                    if (stream == null)
                        throw new IOException("В индексе Git нет .project");
                    description = ResourcesPlugin.getWorkspace().loadProjectDescription(stream);
                }
                catch (IOException | org.eclipse.core.runtime.CoreException error)
                {
                    throw new IllegalStateException("Не удалось прочитать описание проекта из Git", error);
                }
            return description;
        }

        public String getProjectName() { return description().getName(); }

        public String getProjectNature()
        {
            for (String nature : new String[] { "com._1c.g5.v8.dt.core.V8ConfigurationNature",
                "com._1c.g5.v8.dt.core.V8ExternalObjectsNature", "com._1c.g5.v8.dt.core.V8ExtensionNature" })
                if (description().hasNature(nature))
                    return nature;
            throw new IllegalStateException("В описании проекта Git нет поддерживаемого типа конфигурации");
        }

        public synchronized Version getTargetRuntimeVersion()
        {
            if (runtimeVersion == null)
                try (InputStream stream = getFileStream(Path.of("DT-INF/PROJECT.PMF")))
                {
                    runtimeVersion = stream == null ? Version.LATEST
                        : Version.parseVersion(ProjectManifest.parseProjectManifest(stream).get("Runtime-Version"));
                    if (runtimeVersion == Version.EMPTY_VERSION)
                        runtimeVersion = Version.LATEST;
                }
                catch (IOException error) { throw new UncheckedIOException(error); }
            return runtimeVersion;
        }

        private Object importRequest(Class<?> type) throws ReflectiveOperationException
        {
            Object request = type.getConstructor().newInstance();
            var add = type.getMethod("addChangedSource", org.eclipse.core.runtime.IPath.class);
            for (String file : files)
                add.invoke(request, new org.eclipse.core.runtime.Path(file));
            Global.tempLog(LOG, "import request full=" + full + " files=" + files.size());
            return request;
        }

        private Object adapter(Class<?> providerType)
        {
            return Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[] { providerType },
                (proxy, method, arguments) ->
                {
                    if (method.getName().equals("getFullImportRequest"))
                        return importRequest(method.getReturnType());
                    if (method.getDeclaringClass() == Object.class)
                        return switch (method.getName())
                        {
                            case "toString" -> "Git index source: " + prefix;
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new IllegalStateException(method.toString());
                        };
                    // Методы публичного IProjectSourceProvider и четыре метода расширенного интерфейса
                    // подтверждены в JAR. Публичный метод вложенного класса доступен через setAccessible.
                    var target = getClass().getMethod(method.getName(), method.getParameterTypes());
                    target.setAccessible(true);
                    try { return target.invoke(this, arguments); }
                    catch (InvocationTargetException error) { throw error.getTargetException(); }
                });
        }

        @Override
        public List<Path> getFileListRecursively(Path folder)
        {
            String path = folder.toString().replace('\\', '/');
            String start = path.isEmpty() ? "" : path + "/";
            List<Path> result = new ArrayList<>();
            for (String file : files)
                if (file.equals(path) || file.startsWith(start))
                    result.add(Path.of(file));
            Global.tempLog(LOG, "list folder=" + folder + " selected=" + result.size() + " full=" + full);
            return result;
        }

        private synchronized ObjectId id(Path path) throws IOException
        {
            String name = prefix + path.toString().replace('\\', '/');
            if (head == null)
            {
                var entry = index.getEntry(name);
                return entry == null ? null : entry.getObjectId();
            }
            if (!oldIds.containsKey(name))
            {
                try (RevWalk revisions = new RevWalk(repository);
                    TreeWalk tree = TreeWalk.forPath(repository, name, revisions.parseCommit(head).getTree()))
                {
                    oldIds.put(name, tree == null ? null : tree.getObjectId(0));
                }
            }
            return oldIds.get(name);
        }

        @Override
        public boolean fileExists(Path path)
        {
            try { return id(path) != null; }
            catch (IOException error) { throw new UncheckedIOException(error); }
        }

        @Override
        public InputStream getFileStream(Path path)
        {
            Global.tempLog(LOG, "read blob path=" + prefix + path + " previous=" + (head != null));
            try
            {
                ObjectId object = id(path);
                if (object == null)
                    return null;
                byte[] local = workTree.get(object);
                if (local != null)
                    return new java.io.ByteArrayInputStream(local);
                var reader = repository.newObjectReader();
                try
                {
                    InputStream blob = reader.open(object, Constants.OBJ_BLOB).openStream();
                    return new FilterInputStream(blob)
                    {
                        @Override public void close() throws IOException
                        {
                            try { super.close(); }
                            finally { reader.close(); }
                        }
                    };
                }
                catch (Throwable error)
                {
                    reader.close();
                    throw error;
                }
            }
            catch (IOException error) { throw new UncheckedIOException(error); }
        }

        @Override public void stop() { Global.tempLog(LOG, "source stopped full=" + full + " selected=" + files.size()); }
    }
}
