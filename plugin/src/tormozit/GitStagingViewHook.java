package tormozit;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
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
import org.eclipse.egit.ui.UIUtils;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.compare.datasource.GitComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;

/**
 * Проверка состава индекса перед двумя кнопками фиксации панели «Индексирование Git».
 * Поля currentRepository, commitButton, commitAndPushButton подтверждены в StagingView EGit.
 * Изолированный GitComparisonDataSourceDescriptor читает снимок дерева индекса как ревизию;
 * режим EDT "Index" не используется: он дополнительно проверяет Files.exists в рабочем каталоге.
 * Снимок хранится в .tmp рабочего состояния плагина; исходные Git-объекты доступны через alternates.
 * Модель рабочего проекта используется исключительно для адресов переходов из панели «Поиск».
 * Фильтр файлов и его настройка живут отдельно в GitStagingFilterHook.
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
        Display.getDefault().asyncExec(() ->
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
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
            CommitButtons session = new CommitButtons(view, commit, push);
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

    private static record ProjectCheck(IProject project, URI navigationBase) {}
    private static record Findings(IProject project, List<CompareSearchMatch> matches) {}

    private static final class CommitButtons
    {
        private final IViewPart view;
        private final Button commit;
        private final Button push;
        private boolean running;

        CommitButtons(IViewPart view, Button commit, Button push)
        {
            this.view = view;
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
            if (running)
                return;
            running = true;
            try
            {
                if (!confirm())
                    return;
                if (button.isDisposed())
                    return;
                Global.tempLog(LOG, "continue native button=" + button.getText());
                for (Listener listener : original)
                    listener.handleEvent(event);
            }
            finally
            {
                running = false;
            }
        }

        private boolean confirm()
        {
            Object current = Global.getField(view, "currentRepository");
            if (!(current instanceof Repository repository))
            {
                Global.tempLog(LOG, "no repository; native validation");
                return true;
            }
            List<Findings> findings = new ArrayList<>();
            boolean commitEnabled = commit.getEnabled();
            boolean pushEnabled = push.getEnabled();
            DirCache index = null;
            try
            {
                // Этот же штатный предикат отличает «Отправить» от «Фиксировать и отправить».
                Class<?> stateClass = Class.forName("org.eclipse.egit.ui.internal.staging.LazyRepositoryState",
                    false, view.getClass().getClassLoader());
                var constructor = stateClass.getDeclaredConstructor(Repository.class);
                constructor.setAccessible(true);
                Object possible = Global.invoke(view, "isCommitPossible", constructor.newInstance(repository));
                if (!(possible instanceof Boolean))
                    throw new IllegalStateException("Не удалось определить режим кнопки фиксации");
                if (Boolean.FALSE.equals(possible))
                    return true;
                List<ProjectCheck> projects = projects(repository);
                Global.tempLog(LOG, "start repository=" + repository.getDirectory() + " projects=" + projects.size());
                if (projects.isEmpty())
                    return true;
                if (!UIUtils.saveAllEditors(repository, "Фиксация отменена после сохранения редакторов"))
                    return false;
                commit.setEnabled(false);
                push.setEnabled(false);
                // Состав индекса сохраняется до ответа пользователя и передачи управления штатной кнопке.
                index = repository.lockDirCache();
                if (index.hasUnmergedPaths())
                    throw new IllegalStateException("В индексе Git есть неразрешённые конфликты");
                ObjectId head = repository.resolve("HEAD");
                Set<String> changedFiles = changedFiles(repository, index, head);
                DirCache lockedIndex = index;
                Global.tempLog(LOG, "index locked head=" + head + " changedFiles=" + changedFiles.size());
                new ProgressMonitorDialog(view.getSite().getShell()).run(true, true, monitor ->
                {
                    try
                    {
                        check(repository, lockedIndex, projects, findings, head, changedFiles, monitor);
                    }
                    catch (OperationCanceledException canceled) { throw new InterruptedException(); }
                    catch (Exception error) { throw new InvocationTargetException(error); }
                });
                if (commit.isDisposed() || push.isDisposed()
                    || Global.getField(view, "currentRepository") != repository
                    || !java.util.Objects.equals(head, repository.resolve("HEAD")))
                    throw new IllegalStateException("Панель или выбранный репозиторий изменились во время проверки");
                int count = findings.stream().mapToInt(result -> result.matches().size()).sum();
                Global.tempLog(LOG, "finished matches=" + count);
                if (count == 0)
                    return true;
                for (Findings result : findings)
                {
                    if (result.matches().isEmpty())
                        continue;
                    CompareSearchResult search = new CompareSearchResult(result.matches(), null, result.project());
                    search.setQueryText("битые ссылки метаданных");
                    search.setScopeLabel("составе коммита");
                    CompareSearchQuery query = new CompareSearchQuery();
                    search.setQuery(query);
                    query.setSearchResult(search);
                    var status = NewSearchUI.runQueryInForeground(null, query);
                    if (status == null || !status.isOK())
                        throw new IllegalStateException("Не удалось показать битые ссылки в панели Поиск");
                }
                boolean accepted = MessageDialog.openQuestion(view.getSite().getShell(),
                    Global.withPluginWindowTitle(TITLE),
                    "Коммит содержит битые ссылки на метаданные (показаны в панели Поиск). Продолжить?");
                Global.tempLog(LOG, "answer continue=" + accepted);
                if (accepted && (commit.isDisposed() || push.isDisposed()
                    || Global.getField(view, "currentRepository") != repository
                    || !java.util.Objects.equals(head, repository.resolve("HEAD"))))
                    throw new IllegalStateException("Репозиторий изменился во время подтверждения фиксации");
                return accepted;
            }
            catch (InterruptedException | OperationCanceledException canceled)
            {
                Global.tempLog(LOG, "canceled");
                return false;
            }
            catch (Exception error)
            {
                Throwable cause = error instanceof InvocationTargetException wrapped
                    ? wrapped.getTargetException() : error;
                Global.tempLogException(LOG, "failed", cause);
                MessageDialog.openError(view.getSite().getShell(), Global.withPluginWindowTitle(TITLE),
                    "Не удалось проверить ссылки коммита. Фиксация отменена.\n" + cause.getMessage());
                return false;
            }
            finally
            {
                if (index != null)
                    index.unlock();
                if (!commit.isDisposed())
                    commit.setEnabled(commitEnabled);
                if (!push.isDisposed())
                    push.setEnabled(pushEnabled);
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

        private static void check(Repository repository, DirCache index, List<ProjectCheck> projects, List<Findings> findings,
            ObjectId head, Set<String> changedFiles, IProgressMonitor monitor) throws java.io.IOException
        {
            IComparisonDataSourceFactory factory = Global.getOsgiService(IComparisonDataSourceFactory.class);
            if (factory == null)
                throw new IllegalStateException("Недоступна служба источников сравнения EDT");
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
                    if (file.startsWith(prefix))
                        projectFiles.add(file.substring(prefix.length()));
                if (projectFiles.isEmpty())
                {
                    progress.worked(1);
                    continue;
                }
                SubMonitor checking = progress.split(1).setWorkRemaining(100);
                checking.subTask(project.project().getName());
                // Полностью удалённая конфигурация не имеет сохраняющихся владельцев ссылок.
                if (index.getEntry(prefix + "src/Configuration/Configuration.mdo") == null)
                {
                    Global.tempLog(LOG, "configuration removed project=" + project.project().getName());
                    checking.done();
                    continue;
                }
                IndexSnapshot snapshot = IndexSnapshot.create(repository, index);
                IComparisonDataSource source = null;
                try
                {
                    source = factory.create(new GitComparisonDataSourceDescriptor(snapshot.root, snapshot.revision.name(),
                        snapshot.root.resolve(prefix)));
                    if (source == null)
                        throw new IllegalStateException("Не удалось создать модель индекса Git");
                    Global.tempLog(LOG, "import start project=" + project.project().getName());
                    source.startIfNecessary(checking.split(40));
                    checking.checkCanceled();
                    IBmModel model = source.getBmModel();
                    if (model == null)
                        throw new IllegalStateException("Недоступна модель индекса Git");
                    if (model.getEngine().getTopObjectByFqn("Configuration") == null)
                        throw new IllegalStateException("Не удалось импортировать конфигурацию из индекса Git");
                    boolean deleted = false;
                    SubMonitor previousProgress = checking.split(40);
                    if (existed(repository, head, prefix + "src/Configuration/Configuration.mdo"))
                    {
                        IComparisonDataSource previous = factory.create(new GitComparisonDataSourceDescriptor(
                            snapshot.root, head.name(), snapshot.root.resolve(prefix)));
                        if (previous == null)
                            throw new IllegalStateException("Не удалось создать модель предыдущего коммита");
                        try
                        {
                            previous.startIfNecessary(previousProgress.split(30));
                            previousProgress.checkCanceled();
                            if (previous.getBmModel() == null
                                || previous.getBmModel().getEngine().getTopObjectByFqn("Configuration") == null)
                                throw new IllegalStateException("Недоступна модель предыдущего коммита");
                            deleted = MdReferenceSupport.hasDeletedObjects(previous, source, projectFiles,
                                previousProgress.split(10));
                        }
                        finally { previous.stop(); }
                    }
                    previousProgress.done();
                    Global.tempLog(LOG, "scope project=" + project.project().getName() + " all=" + deleted
                        + " files=" + projectFiles);
                    List<CompareSearchMatch> matches = MdReferenceSupport.findInModel(source,
                        project.navigationBase(), deleted ? null : projectFiles, checking.split(20));
                    findings.add(new Findings(project.project(), matches));
                    Global.tempLog(LOG, "project checked=" + project.project().getName() + " matches=" + matches.size());
                }
                finally
                {
                    if (source != null)
                        source.stop();
                    snapshot.close();
                    Global.tempLog(LOG, "source stopped project=" + project.project().getName());
                }
            }
            progress.done();
        }
    }

    /** В исходный репозиторий ничего не записывается: дерево и служебная ревизия живут только в .tmp. */
    private static final class IndexSnapshot implements AutoCloseable
    {
        private final Path root;
        private final Repository repository;
        private ObjectId revision;

        private IndexSnapshot(Path root, Repository repository)
        {
            this.root = root;
            this.repository = repository;
        }

        static IndexSnapshot create(Repository original, DirCache index) throws java.io.IOException
        {
            if (!(original.getObjectDatabase() instanceof ObjectDirectory objects))
                throw new IllegalStateException("Недоступно файловое хранилище объектов Git");
            Path parent = Activator.getDefault().getStateLocation().toFile().toPath().resolve(".tmp/commit-check");
            Files.createDirectories(parent);
            Path root = Files.createTempDirectory(parent, "snapshot-");
            Repository temporary;
            try
            {
                temporary = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile())
                    .setWorkTree(root.toFile()).build();
            }
            catch (java.io.IOException | RuntimeException error)
            {
                remove(root);
                throw error;
            }
            IndexSnapshot snapshot = new IndexSnapshot(root, temporary);
            try
            {
                temporary.create(false);
                Path info = root.resolve(".git/objects/info");
                Files.createDirectories(info);
                Files.writeString(info.resolve("alternates"),
                    objects.getDirectory().getAbsolutePath().replace('\\', '/') + "\n", StandardCharsets.UTF_8);
                try (var inserter = temporary.newObjectInserter())
                {
                    CommitBuilder commit = new CommitBuilder();
                    commit.setTreeId(index.writeTree(inserter));
                    PersonIdent identity = new PersonIdent("EDT Comfort", "comfort@localhost");
                    commit.setAuthor(identity);
                    commit.setCommitter(identity);
                    commit.setMessage("Temporary index snapshot for metadata reference validation");
                    snapshot.revision = inserter.insert(commit);
                    inserter.flush();
                }
                Global.tempLog(LOG, "snapshot root=" + root + " revision=" + snapshot.revision);
                return snapshot;
            }
            catch (java.io.IOException | RuntimeException error)
            {
                snapshot.close();
                throw error;
            }
        }

        @Override
        public void close()
        {
            Repository cached = RepositoryCache.INSTANCE.getRepository(root.resolve(".git").toFile());
            if (cached != null && cached != repository)
                cached.close();
            repository.close();
            remove(root);
        }

        private static void remove(Path root)
        {
            try (var paths = Files.walk(root))
            {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
                Global.tempLog(LOG, "snapshot removed root=" + root);
            }
            catch (Exception error)
            {
                Global.tempLogException(LOG, "snapshot cleanup failed root=" + root, error);
            }
        }
    }
}
