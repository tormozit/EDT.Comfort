package tormozit;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.BytesRef;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.IDerivedDataStatusListener;
import com._1c.g5.v8.derived.context.IObjectDerivedDataContext;
import com._1c.g5.v8.derived.pipeline.PipelineSegmentStatus;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.search.core.SearchFor;
import com._1c.g5.v8.dt.search.core.text.ITextSearchIndex;
import com._1c.g5.v8.dt.search.core.text.ITextSearchIndexProvider;
import com._1c.g5.v8.dt.search.core.text.TextSearchQueryFactory;

/**
 * Свежесть полнотекстового индекса EDT ({@code TextSearchIndex}) на вкладке «Поиск в
 * конфигурациях» — других вкладок окна «Поиск», которые им пользуются, нет. Индекс считается
 * производными данными проекта: сегменты {@code FTS_INDEXING_SEGMENT}/{@code FTS_CLEANER_SEGMENT};
 * их остаток работы — из {@code IDerivedDataManager} (статус и слушатель статуса). Пока индекс
 * обновляется — крутилка как в панели «Проблемы конфигурации».
 */
final class IndexFreshness
{
    private static final String INDEXING_SEGMENT = "FTS_INDEXING_SEGMENT";
    private static final Set<String> SEGMENTS = Set.of(INDEXING_SEGMENT, "FTS_CLEANER_SEGMENT");
    private static final String RECOVERY_COUNT = " Обновлено устаревших элементов: ";
    private static final AtomicInteger REPAIR_REVISION = new AtomicInteger();
    private static final ISchedulingRule REPAIR_RULE = new ISchedulingRule()
    {
        @Override public boolean contains(ISchedulingRule rule) { return rule == this; }
        @Override public boolean isConflicting(ISchedulingRule rule) { return rule == this; }
    };
    private static final int POLL_MS = 300;
    private static final String TOOLTIP = "Проверяется наличие записей для непустых BSL-модулей в папках src."
        + " Для сохранений, замеченных с открытия окна, также сверяется содержимое индекса.";
    private static final boolean SHOW_INDEX_REMOVAL_TEST_BUTTON = false;
    private static final String REMOVE_LABEL = "Убить индекс по текущему модулю";

    private final Label label;
    private final String tooltip;
    private final Map<IDerivedDataManager, IDerivedDataStatusListener> listeners = new HashMap<>();
    private WaitSpinner spinner;
    private boolean refreshPending;
    private boolean checkNeeded = true;
    private boolean queueWasBusy;
    private boolean searchActive = true;
    private Job checkJob;
    private String checkStatus = "Проверка полноты ожидает запуска.";
    private final AtomicInteger repairedCount = new AtomicInteger();
    private final Set<IFile> changedFiles = new HashSet<>();
    private IntConsumer afterRepair;
    private int searchRevision = -1;
    private BooleanSupplier isCurrentSearch = () -> true;
    private boolean finishAfterClose;
    private Composite analysisStatus;
    private volatile AnalysisProgress analysisProgress;

    private record AnalysisProgress(int percent, int found) {}

    private IndexFreshness(Label label, String tooltip)
    {
        this.label = label;
        this.tooltip = tooltip;
    }

    /** Подпись состояния индекса в нижней строке страницы предпросмотра рефакторинга. */
    static void installInRefactoring(Composite root, IntConsumer afterRepair)
    {
        if (root == null || root.isDisposed() || !(root.getLayout() instanceof GridLayout grid))
            return;
        installAnalysisStatus(root, grid).afterRepair = afterRepair;
    }

    static void install(Object page)
    {
        if (!(Global.invoke(page, "getControl") instanceof Composite root) || root.isDisposed() //$NON-NLS-1$
            || !(root.getLayout() instanceof GridLayout grid))
            return;
        IndexFreshness freshness = install(root, grid, TOOLTIP);
        freshness.finishAfterClose = true;
        freshness.listenForSavedModules();
        if (SHOW_INDEX_REMOVAL_TEST_BUTTON)
            freshness.installRemoveButton(root, grid);
        root.getDisplay().timerExec(500, () -> dumpLayout(freshness.label));
    }

    static IndexFreshness installInSearchResults(Composite root, GridLayout grid)
    {
        IndexFreshness freshness = installAnalysisStatus(root, grid);
        freshness.searchActive = false;
        return freshness;
    }

    private static IndexFreshness installAnalysisStatus(Composite root, GridLayout grid)
    {
        Composite status = new Composite(root, SWT.NONE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, grid.numColumns, 1));
        GridLayout statusLayout = new GridLayout();
        statusLayout.marginHeight = 0;
        statusLayout.marginWidth = 0;
        status.setLayout(statusLayout);
        IndexFreshness freshness = install(status, statusLayout,
            "Проверяется наличие записей для непустых BSL-модулей в папках src.");
        freshness.analysisStatus = status;
        freshness.showAnalysisStatus(false);
        return freshness;
    }

    void watchSearch(IntConsumer onRepair)
    {
        afterRepair = onRepair;
        searchActive = true;
        checkNeeded = true;
        refresh();
    }

    static int repairRevision()
    {
        return REPAIR_REVISION.get();
    }

    void watchSearch(int revision, BooleanSupplier isCurrent, IntConsumer onRepair)
    {
        searchRevision = revision;
        isCurrentSearch = isCurrent;
        watchSearch(onRepair);
    }

    void stopWatchingSearch()
    {
        afterRepair = null;
        searchActive = false;
        refresh();
    }

    private static IndexFreshness install(Composite root, GridLayout grid, String tooltip)
    {
        Label label = new Label(root, SWT.NONE);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, grid.numColumns, 1));
        label.setToolTipText(TooltipText.wrap(label, tooltip + Global.pluginSignForTooltip()));
        IndexFreshness freshness = new IndexFreshness(label, tooltip);
        freshness.listen();
        label.addDisposeListener(e ->
        {
            freshness.unlisten();
            if (freshness.checkJob != null && !freshness.finishAfterClose)
                freshness.checkJob.cancel();
        });
        freshness.startPolling();
        return freshness;
    }

    private void listenForSavedModules()
    {
        Display display = label.getDisplay();
        IResourceChangeListener listener = event ->
        {
            Set<IFile> saved = new HashSet<>();
            try
            {
                event.getDelta().accept(delta ->
                {
                    if (delta.getResource() instanceof IFile file
                        && "src".equals(file.getProjectRelativePath().segment(0))
                        && "bsl".equalsIgnoreCase(file.getFileExtension())
                        && (delta.getKind() == IResourceDelta.ADDED || delta.getKind() == IResourceDelta.CHANGED
                            && (delta.getFlags() & (IResourceDelta.CONTENT | IResourceDelta.REPLACED)) != 0))
                        saved.add(file);
                    return true;
                });
            }
            catch (CoreException ex)
            {
                Global.tempLog("search-index-coverage", "Не удалось прочитать изменения ресурсов: " + ex);
            }
            if (!saved.isEmpty())
                display.asyncExec(() ->
                {
                    if (label.isDisposed())
                        return;
                    changedFiles.addAll(saved);
                    checkNeeded = true;
                    refresh();
                });
        };
        ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
        label.addDisposeListener(e -> ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener));
    }

    private void installRemoveButton(Composite root, GridLayout grid)
    {
        Button button = new Button(root, SWT.PUSH);
        button.setText(REMOVE_LABEL);
        button.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, grid.numColumns, 1));
        button.setToolTipText(TooltipText.wrap(button, "Имитирует пропуск в поиске. Удаляет из полнотекстового"
            + " индекса записи активного модуля BSL." + Global.pluginSignForTooltip()));
        IWorkbenchPage page = searchWorkbenchPage(root.getShell());
        button.addListener(SWT.Selection, e ->
        {
            var editor = page == null ? null : page.getActiveEditor();
            var bsl = GetRef.getActiveBslEditor(editor);
            IFile file = bsl != null && bsl.getEditorInput() != null
                ? bsl.getEditorInput().getAdapter(IFile.class) : null;
            Global.tempLog("search-index-coverage", "Нажата кнопка удаления: редактор="
                + (editor == null ? "нет" : editor.getClass().getName()) + ", файл="
                + (file == null ? "нет" : file.getFullPath()));
            if (file == null || !file.isAccessible())
            {
                ToastNotification.show(REMOVE_LABEL, "Откройте модуль BSL и повторите команду");
                return;
            }
            removeModule(button, file);
        });
    }

    private static IWorkbenchPage searchWorkbenchPage(Shell dialog)
    {
        for (var window : PlatformUI.getWorkbench().getWorkbenchWindows())
            for (var owner = dialog.getParent(); owner != null; owner = owner.getParent())
                if (owner == window.getShell())
                    return window.getActivePage();
        return Global.getActivePage();
    }

    private void removeModule(Button button, IFile file)
    {
        String fullName = GetRef.resolveSetTextModuleName(file);
        String moduleName = fullName.isEmpty() ? file.getFullPath().toPortableString()
            : fullName + " (" + file.getProject().getName() + ")";
        button.setEnabled(false);
        Display display = button.getDisplay();
        Job.create(REMOVE_LABEL, (ICoreRunnable)monitor ->
        {
            String result;
            try
            {
                ITextSearchIndex index = requireIndex(Global.getOsgiService(ITextSearchIndexProvider.class),
                    file.getProject());
                boolean presentBefore = containsIndexedFile(index, file, monitor);
                index.removeFile(List.of(file));
                boolean presentAfter = containsIndexedFile(index, file, monitor);
                Global.tempLog("search-index-coverage", "Проверка удаления " + file.getFullPath()
                    + ": до=" + presentBefore + ", после=" + presentAfter);
                if (presentAfter)
                    result = "Записи модуля по-прежнему находятся в индексе.";
                else if (!presentBefore)
                    result = "Модуль уже отсутствовал в индексе перед удалением.";
                else
                    result = "Записи модуля удалены из индекса.";
            }
            catch (IOException | RuntimeException ex)
            {
                result = "Не удалось подтвердить удаление записей модуля из индекса.";
                Global.tempLog("search-index-coverage", result + " " + file.getFullPath() + ": " + ex);
            }
            String message = moduleName + "\n" + result;
            display.asyncExec(() ->
            {
                ToastNotification.show(REMOVE_LABEL, message, 6000);
                if (!button.isDisposed())
                    button.setEnabled(true);
            });
        }).schedule();
    }

    private void startCoverageCheck()
    {
        checkNeeded = false;
        repairedCount.set(0);
        Set<IFile> saved = Set.copyOf(changedFiles);
        changedFiles.clear();
        int revisionBefore = REPAIR_REVISION.get();
        analysisProgress = new AnalysisProgress(0, 0);
        Display display = label.getDisplay();
        checkJob = Job.create("Проверка полноты индекса поиска", (ICoreRunnable)monitor ->
        {
            List<String> missing = new ArrayList<>();
            String status;
            boolean complete = false;
            try
            {
                checkCoverage(missing, saved, monitor);
                complete = missing.isEmpty();
                status = complete ? "Индекс поиска актуален."
                    : "Индекс содержит пропуски или устаревшие записи. Модулей: " + missing.size() + ".";
                Global.tempLog("search-index-coverage", status + " " + missing);
            }
            catch (OperationCanceledException ex)
            {
                status = "Проверка полноты прервана.";
            }
            catch (CoreException | IOException | ReflectiveOperationException | RuntimeException ex)
            {
                status = "Не удалось проверить полноту индекса.";
                Global.tempLog("search-index-coverage", status + " " + ex);
            }
            String result = status;
            boolean confirmed = complete;
            display.asyncExec(() ->
            {
                if (label.isDisposed())
                    return;
                analysisProgress = null;
                checkJob = null;
                if (!checkNeeded)
                {
                    checkStatus = result;
                    String detail = missing.isEmpty() ? "" : "\n" + String.join("\n",
                        missing.subList(0, Math.min(5, missing.size())));
                    label.setToolTipText(TooltipText.wrap(label, tooltip + detail + Global.pluginSignForTooltip()));
                    if (!confirmed && analysisStatus != null && analysisStatus.getParent().isVisible()
                        && isCurrentSearch.getAsBoolean())
                        ToastNotification.show("Индекс поиска", result, 6000);
                }
                refresh();
                int revision = REPAIR_REVISION.get();
                int updated = revision - (searchRevision >= 0 ? searchRevision : revisionBefore);
                if (searchRevision >= 0)
                    searchRevision = revision;
                if (afterRepair != null && updated > 0 && isCurrentSearch.getAsBoolean())
                    afterRepair.accept(updated);
            });
        });
        checkJob.setSystem(true);
        checkJob.setRule(REPAIR_RULE);
        checkJob.schedule();
    }

    private void checkCoverage(List<String> missing, Set<IFile> saved, IProgressMonitor monitor)
        throws IOException, CoreException, ReflectiveOperationException
    {
        List<IProject> projects = workspaceProjects();
        if (projects.isEmpty())
            throw new IOException("Нет доступных проектов EDT");
        FtsAccess access = FtsAccess.create();
        if (access == null)
            throw new IOException("Быстрая сверка не поддерживается для этой версии EDT");
        var provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        var indexProvider = Global.getOsgiService(ITextSearchIndexProvider.class);
        boolean checkedSource = false;
        boolean unsupportedProject = false;
        int found = 0;
        for (int projectIndex = 0; projectIndex < projects.size(); projectIndex++)
        {
            IProject project = projects.get(projectIndex);
            updateAnalysis(projectIndex, projects.size(), 0, found);
            monitor.subTask(project.getName());
            var source = project.getFolder("src");
            if (!source.isAccessible())
                continue;
            ITextSearchIndex textIndex = requireIndex(indexProvider, project);
            if (!FtsAccess.isLocalIndex(textIndex))
            {
                unsupportedProject = true;
                Global.tempLog("search-index-coverage", "Пропущен индекс с библиотеками: " + project.getName());
                continue;
            }
            checkedSource = true;
            Set<String> indexed = access.paths(project, null, monitor);
            updateAnalysis(projectIndex, projects.size(), 25, found);
            Set<IFile> changed = new HashSet<>();
            for (IFile file : saved)
                if (project.equals(file.getProject()) && file.isAccessible())
                    changed.add(file);
            List<IFile> candidates = new ArrayList<>();
            source.accept(resource ->
            {
                if (monitor.isCanceled())
                    throw new OperationCanceledException();
                if (resource instanceof IFile file && "bsl".equalsIgnoreCase(file.getFileExtension())
                    && !indexed.contains(file.getProjectRelativePath().toString()))
                    candidates.add(file);
                return true;
            });
            updateAnalysis(projectIndex, projects.size(), 50, found);
            Set<IFile> repair = new HashSet<>();
            int total = Math.max(1, candidates.size() + changed.size());
            int checked = 0;
            for (IFile file : candidates)
            {
                if (monitor.isCanceled())
                    throw new OperationCanceledException();
                if (file.isAccessible() && hasText(file))
                    repair.add(file);
                updateAnalysis(projectIndex, projects.size(), 50 + 50 * ++checked / total, found + repair.size());
            }
            for (IFile file : changed)
            {
                if (!access.isCurrent(file, monitor))
                    repair.add(file);
                updateAnalysis(projectIndex, projects.size(), 50 + 50 * ++checked / total, found + repair.size());
            }
            found += repair.size();
            updateAnalysis(projectIndex, projects.size(), 100, found);
            Global.tempLog("search-index-coverage", "Сверка " + project.getName() + ": файлов в индексе="
                + indexed.size() + ", сверено сохранений=" + changed.size() + ", кандидатов=" + repair.size());
            if (repair.isEmpty())
                continue;
            analysisProgress = null;
            IDerivedDataManager manager = provider == null ? null : provider.get(project);
            if (manager != null && waitForIndex(manager, monitor))
            {
                access.removeCurrent(project, repair, changed, monitor);
                Map<Object, Collection<String>> contexts = repair.isEmpty() ? Map.of()
                    : access.enqueue(manager, List.copyOf(repair));
                if (!contexts.isEmpty())
                {
                    Global.tempLog("search-index-coverage", "Поставлены в очередь FTS: " + repair);
                    if (waitForRepair(manager, contexts, monitor))
                    {
                        int submitted = repair.size();
                        int removed = access.removeCurrent(project, repair, changed, monitor);
                        int confirmed = submitted - repair.size() - removed;
                        repairedCount.addAndGet(confirmed);
                        REPAIR_REVISION.addAndGet(confirmed);
                        Global.tempLog("search-index-coverage", "Подтверждено обновление: " + confirmed
                            + ", не восстановлены: " + repair);
                    }
                }
            }
            for (IFile file : repair)
                missing.add(file.getFullPath().toPortableString());
        }
        if (!checkedSource)
            throw new IOException("В открытых проектах EDT нет доступных папок src");
        if (unsupportedProject)
            throw new IOException("Сверка индексов проектов с библиотеками не поддерживается");
    }

    private void updateAnalysis(int projectIndex, int projects, int phase, int found)
    {
        int percent = (100 * projectIndex + phase) / projects;
        AnalysisProgress previous = analysisProgress;
        if (previous == null || previous.percent() != percent || previous.found() != found)
            analysisProgress = new AnalysisProgress(percent, found);
    }

    private static boolean waitForIndex(IDerivedDataManager manager, IProgressMonitor monitor)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!manager.isComputed(SEGMENTS))
        {
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            if (System.nanoTime() >= deadline)
                return false;
            try
            {
                Thread.sleep(POLL_MS);
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                throw new OperationCanceledException();
            }
        }
        return true;
    }

    private static boolean waitForRepair(IDerivedDataManager manager, Map<Object, Collection<String>> contexts,
        IProgressMonitor monitor)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try
        {
            while (!monitor.isCanceled() && System.nanoTime() < deadline)
                if (manager.waitComputationExt(contexts, 1000L))
                    return true;
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new OperationCanceledException();
        }
        if (monitor.isCanceled())
            throw new OperationCanceledException();
        Global.tempLog("search-index-coverage", "Истекло ожидание контекстов FTS: " + contexts.keySet());
        return false;
    }

    private static boolean containsIndexedFile(ITextSearchIndex index, IFile target, IProgressMonitor monitor)
        throws IOException
    {
        FtsAccess access = FtsAccess.create();
        if (access != null && FtsAccess.isLocalIndex(index))
            return !access.paths(target.getProject(), target.getProjectRelativePath().toString(), monitor).isEmpty();
        boolean[] found = { false };
        var query = TextSearchQueryFactory.INSTANCE.create("*", true, Set.of(SearchFor.LANGUAGE_ELEMENTS),
            Set.of(), Set.of());
        index.search(query, (file, text, offset, length, fileOffset, line) ->
        {
            if (target.equals(file))
                found[0] = true;
        }, monitor);
        return found[0];
    }

    private static final class FtsAccess
    {
        private static final int SEGMENT_COUNT = 16;
        private static final int BATCH_SIZE = 100;
        private static final Set<String> STORED_BLOCK_FIELDS = Set.of("text", "lineNumber", "lineOffset", "blockOffset");
        private static final Set<String> SUPPORTED = Set.of("12.3.100.v202605050943", "13.0.0.v202607090722",
            "14.0.0.v202610211527");
        private final Bundle bundle;

        private FtsAccess(Bundle bundle)
        {
            this.bundle = bundle;
        }

        private static FtsAccess create()
        {
            Bundle bundle = FrameworkUtil.getBundle(ITextSearchIndex.class);
            return bundle != null && SUPPORTED.contains(bundle.getVersion().toString()) ? new FtsAccess(bundle) : null;
        }

        private static boolean isLocalIndex(ITextSearchIndex index)
        {
            return index.getClass().getName().equals("com._1c.g5.v8.dt.internal.search.core.text.TextSearchIndex");
        }

        private Set<String> paths(IProject project, String target, IProgressMonitor monitor) throws IOException
        {
            Path root = Platform.getStateLocation(bundle).toFile().toPath().resolve("index").resolve(project.getName());
            Set<String> paths = new HashSet<>();
            int first = target == null ? 0 : segment(target);
            int limit = target == null ? SEGMENT_COUNT : first + 1;
            for (int segment = first; segment < limit; segment++)
            {
                Path path = root.resolve(Integer.toString(segment));
                if (!Files.isDirectory(path))
                    continue;
                try (var directory = new NIOFSDirectory(path))
                {
                    if (!DirectoryReader.indexExists(directory))
                        continue;
                    try (var reader = DirectoryReader.open(directory))
                    {
                        for (var leaf : reader.leaves())
                        {
                            var terms = leaf.reader().terms("filePath");
                            if (terms == null)
                                continue;
                            var iterator = terms.iterator();
                            var live = leaf.reader().getLiveDocs();
                            PostingsEnum postings = null;
                            if (target != null)
                            {
                                if (iterator.seekExact(new BytesRef(target))
                                    && hasLiveDocument(iterator.postings(null, PostingsEnum.NONE), live))
                                    return Set.of(target);
                                continue;
                            }
                            for (var term = iterator.next(); term != null; term = iterator.next())
                            {
                                if (monitor.isCanceled())
                                    throw new OperationCanceledException();
                                if (live == null
                                    || hasLiveDocument(postings = iterator.postings(postings, PostingsEnum.NONE), live))
                                    paths.add(term.utf8ToString());
                            }
                        }
                    }
                }
            }
            return paths;
        }

        private static boolean hasLiveDocument(PostingsEnum postings, org.apache.lucene.util.Bits live)
            throws IOException
        {
            for (int doc = postings.nextDoc(); doc != PostingsEnum.NO_MORE_DOCS; doc = postings.nextDoc())
                if (live == null || live.get(doc))
                    return true;
            return false;
        }

        private int removeCurrent(IProject project, Set<IFile> candidates, Set<IFile> changed,
            IProgressMonitor monitor) throws IOException, CoreException, ReflectiveOperationException
        {
            Set<String> indexed = paths(project, null, monitor);
            int removed = 0;
            for (Iterator<IFile> iterator = candidates.iterator(); iterator.hasNext();)
            {
                if (monitor.isCanceled())
                    throw new OperationCanceledException();
                IFile file = iterator.next();
                if (!file.isAccessible())
                {
                    iterator.remove();
                    removed++;
                }
                else if (changed.contains(file) ? isCurrent(file, monitor)
                    : indexed.contains(file.getProjectRelativePath().toString()))
                    iterator.remove();
            }
            return removed;
        }

        private record Block(String text, long line, long offset, long blockOffset) {}

        private boolean isCurrent(IFile file, IProgressMonitor monitor)
            throws IOException, CoreException, ReflectiveOperationException
        {
            long stamp = file.getModificationStamp();
            Class<?> iteratorClass = bundle.loadClass(
                "com._1c.g5.v8.dt.internal.search.core.text.FileTextBlockIterator");
            Class<?> blockClass = bundle.loadClass(iteratorClass.getName() + "$FileTextBlock");
            Field text = blockField(blockClass, "text");
            Field line = blockField(blockClass, "lineNumber");
            Field offset = blockField(blockClass, "lineOffset");
            Field part = blockField(blockClass, "isPartOfLineBlock");
            Field blockOffset = blockField(blockClass, "textBlockOffset");
            Map<Block, Integer> difference = new HashMap<>();
            try (Reader source = new InputStreamReader(file.getContents(), StandardCharsets.UTF_8))
            {
                Object blocks = iteratorClass.getConstructor(Reader.class).newInstance(source);
                try (Closeable closeable = (Closeable) blocks)
                {
                    Iterator<?> iterator = (Iterator<?>) blocks;
                    while (iterator.hasNext())
                    {
                        if (monitor.isCanceled())
                            throw new OperationCanceledException();
                        Object block = iterator.next();
                        var key = new Block((String) text.get(block), line.getLong(block), offset.getLong(block),
                            part.getBoolean(block) ? blockOffset.getLong(block) : 0);
                        difference.merge(key, 1, Integer::sum);
                    }
                }
            }
            subtractIndexedBlocks(file, difference, monitor);
            return stamp == file.getModificationStamp() && difference.values().stream().allMatch(count -> count == 0);
        }

        private static Field blockField(Class<?> type, String name) throws NoSuchFieldException
        {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private void subtractIndexedBlocks(IFile file, Map<Block, Integer> difference, IProgressMonitor monitor)
            throws IOException
        {
            String relative = file.getProjectRelativePath().toString();
            Path path = Platform.getStateLocation(bundle).toFile().toPath().resolve("index")
                .resolve(file.getProject().getName()).resolve(Integer.toString(segment(relative)));
            if (!Files.isDirectory(path))
                return;
            try (var directory = new NIOFSDirectory(path))
            {
                if (!DirectoryReader.indexExists(directory))
                    return;
                try (var reader = DirectoryReader.open(directory))
                {
                    for (var leaf : reader.leaves())
                    {
                        var terms = leaf.reader().terms("filePath");
                        if (terms == null)
                            continue;
                        var iterator = terms.iterator();
                        if (!iterator.seekExact(new BytesRef(relative)))
                            continue;
                        var live = leaf.reader().getLiveDocs();
                        var fields = leaf.reader().storedFields();
                        var postings = iterator.postings(null, PostingsEnum.NONE);
                        for (int doc = postings.nextDoc(); doc != PostingsEnum.NO_MORE_DOCS; doc = postings.nextDoc())
                        {
                            if (monitor.isCanceled())
                                throw new OperationCanceledException();
                            if (live != null && !live.get(doc))
                                continue;
                            var stored = fields.document(doc, STORED_BLOCK_FIELDS);
                            var key = new Block(stored.get("text"), Long.parseLong(stored.get("lineNumber")),
                                Long.parseLong(stored.get("lineOffset")),
                                stored.get("blockOffset") == null ? 0 : Long.parseLong(stored.get("blockOffset")));
                            difference.merge(key, -1, Integer::sum);
                        }
                    }
                }
            }
        }

        private Map<Object, Collection<String>> enqueue(IDerivedDataManager manager, List<IFile> files)
            throws ReflectiveOperationException
        {
            Class<? extends IObjectDerivedDataContext> contextClass = bundle
                .loadClass("com._1c.g5.v8.dt.internal.search.core.derived.TextSearchIndexingContext")
                .asSubclass(IObjectDerivedDataContext.class);
            var addFilePaths = contextClass.getMethod("addFilePaths", Set.class);
            Map<Integer, List<IFile>> bySegment = new HashMap<>();
            for (IFile file : files)
                bySegment.computeIfAbsent(segment(file.getProjectRelativePath().toString()), ignored -> new ArrayList<>())
                    .add(file);
            String operation = "comfort-fts-" + UUID.randomUUID();
            Map<Object, Collection<String>> contexts = new HashMap<>();
            boolean scheduled = manager.updateDerivedData((session, model) ->
            {
                for (var entry : bySegment.entrySet())
                {
                    List<IFile> group = entry.getValue();
                    for (int start = 0; start < group.size(); start += BATCH_SIZE)
                    {
                        String key = operation + "-" + entry.getKey() + "-" + start;
                        var context = session.getTypedObjectContext(key, EcorePackage.Literals.EOBJECT,
                            INDEXING_SEGMENT, contextClass);
                        try
                        {
                            addFilePaths.invoke(context, Set.copyOf(group.subList(start,
                                Math.min(start + BATCH_SIZE, group.size()))));
                            contexts.put(context.getObjectId(), Set.of(INDEXING_SEGMENT));
                        }
                        catch (ReflectiveOperationException ex)
                        {
                            throw new IllegalStateException("Не удалось заполнить контекст индексации EDT", ex);
                        }
                    }
                }
            }, 0L, operation);
            return scheduled ? contexts : Map.of();
        }

        private static int segment(String path)
        {
            return path.hashCode() & (SEGMENT_COUNT - 1);
        }
    }

    private static boolean hasText(IFile file) throws IOException, CoreException
    {
        try (var reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
                if (!line.replace("\uFEFF", "").isBlank())
                    return true;
        }
        return false;
    }

    private static ITextSearchIndex requireIndex(ITextSearchIndexProvider provider, IProject project)
        throws IOException
    {
        ITextSearchIndex index = provider == null ? null : provider.get(project);
        if (index == null)
            throw new IOException("Индекс недоступен: " + project.getName());
        return index;
    }

    // #region agent log
    /** Временная диагностика: где в окне «Поиск» стоят подпись индекса и флажок «Открыть результат…». */
    private static void dumpLayout(Label label)
    {
        if (label.isDisposed())
            return;
        StringBuilder sb = new StringBuilder("label parents:\n");
        for (Composite c = label.getParent(); c != null; c = c.getParent())
            sb.append("  ").append(c.getClass().getName()).append(" layout=").append(describeLayout(c))
                .append(" bounds=").append(c.getBounds()).append('\n');
        Shell sh = label.getShell();
        sb.append("label bounds=").append(label.getBounds()).append(" data=").append(label.getLayoutData())
            .append("\ntree:\n");
        dumpTree(sh, 0, sb);
        Global.tempLog("indexFreshnessLayout", sb.toString());
    }

    private static String describeLayout(Composite c)
    {
        return c.getLayout() instanceof GridLayout g ? "Grid cols=" + g.numColumns + " eq=" + g.makeColumnsEqualWidth
            : String.valueOf(c.getLayout());
    }

    private static void dumpTree(Composite parent, int depth, StringBuilder sb)
    {
        if (depth > 7)
            return;
        for (org.eclipse.swt.widgets.Control child : parent.getChildren())
        {
            sb.append("  ".repeat(depth)).append(child.getClass().getSimpleName()).append(" b=")
                .append(child.getBounds()).append(" data=").append(child.getLayoutData());
            if (child instanceof org.eclipse.swt.widgets.Button b)
                sb.append(" text='").append(b.getText()).append('\'');
            if (child instanceof Label l)
                sb.append(" text='").append(l.getText()).append('\'');
            if (child instanceof Composite c)
                sb.append(" layout=").append(describeLayout(c));
            sb.append('\n');
            if (child instanceof Composite c && !(child instanceof org.eclipse.swt.widgets.Table)
                && !(child instanceof org.eclipse.swt.widgets.Tree) && !(child instanceof org.eclipse.swt.widgets.Group))
                dumpTree(c, depth + 1, sb);
        }
    }
    // #endregion

    /** Начало отставания индекса событием статуса не сообщается — опрос, пока окно открыто. */
    private void startPolling()
    {
        Display display = label.getDisplay();
        display.timerExec(0, new Runnable()
        {
            @Override
            public void run()
            {
                if (label.isDisposed())
                    return;
                refresh();
                display.timerExec(POLL_MS, this);
            }
        });
    }

    private void listen()
    {
        for (IDerivedDataManager manager : managers())
        {
            IDerivedDataStatusListener listener = status ->
            {
                scheduleRefresh();
            };
            manager.addStatusListener(listener);
            listeners.put(manager, listener);
        }
    }

    private void unlisten()
    {
        listeners.forEach(IDerivedDataManager::removeStatusListener);
        listeners.clear();
    }

    /** Слушатель зовётся из потоков конвейера часто — обновляем подпись не чаще раза за проход UI. */
    private void scheduleRefresh()
    {
        synchronized (this)
        {
            if (refreshPending)
                return;
            refreshPending = true;
        }
        Display display = label.isDisposed() ? null : label.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            synchronized (this)
            {
                refreshPending = false;
            }
            refresh();
        });
    }

    private void refresh()
    {
        if (label.isDisposed())
            return;
        boolean busy = busy(listeners.keySet());
        String text = coverageText(busy);
        if (!text.equals(label.getText()))
        {
            label.setText(text);
            label.getParent().layout(true, true);
        }
        boolean working = busy || checkJob != null;
        if (working && (spinner == null || spinner.isDisposed()))
            spinner = WaitSpinner.attach(label, tooltip);
        if (spinner != null)
            spinner.setActive(working);
        showAnalysisStatus(checkJob != null && isCurrentSearch.getAsBoolean());
    }

    private void showAnalysisStatus(boolean visible)
    {
        if (analysisStatus == null || analysisStatus.isDisposed())
            return;
        GridData data = (GridData) analysisStatus.getLayoutData();
        if (data.exclude == !visible)
            return;
        data.exclude = !visible;
        analysisStatus.setVisible(visible);
        analysisStatus.getParent().layout(true, true);
    }

    private String coverageText(boolean busy)
    {
        if (busy && !queueWasBusy && checkJob == null)
            checkNeeded = true;
        queueWasBusy = busy;
        if (searchActive && checkNeeded && checkJob == null)
            startCoverageCheck();

        String count = RECOVERY_COUNT + repairedCount.get();
        AnalysisProgress progress = analysisProgress;
        if (checkJob != null && progress != null)
            return "Обновляется индекс поиска. Поиск устаревших: " + progress.percent() + "%. Найдено: "
                + progress.found();
        if (busy || checkJob != null)
            return "Обновляется индекс поиска." + count;
        return checkStatus + count;
    }

    /**
     * Индекс отстаёт, если сегмент не досчитан или у него есть остаток работы.
     * Отставание после сохранения модуля: сегмент не досчитан, а карта активных сегментов при этом
     * пуста и событий статуса нет (лог 17.10.2026, ~17 с).
     */
    private static boolean busy(Iterable<IDerivedDataManager> managers)
    {
        boolean busy = false;
        for (IDerivedDataManager manager : managers)
        {
            if (!manager.isComputed(SEGMENTS.toArray(String[]::new)))
                busy = true;
            DerivedDataStatus status = manager.getDerivedDataStatus();
            Map<String, PipelineSegmentStatus> active = status != null ? status.getActivePipelineSegments() : null;
            if (active == null)
                continue;
            for (Map.Entry<String, PipelineSegmentStatus> segment : active.entrySet())
                if (SEGMENTS.contains(segment.getKey()) && segment.getValue() != null
                    && segment.getValue().getRemainingUnitsOfWork() > 0)
                {
                    busy = true;
                }
        }
        return busy;
    }

    private static List<IDerivedDataManager> managers()
    {
        List<IDerivedDataManager> managers = new ArrayList<>();
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        if (provider == null)
            return managers;
        for (IProject project : workspaceProjects())
        {
            IDerivedDataManager manager = provider.get(project);
            if (manager != null && !managers.contains(manager))
            {
                managers.add(manager);
            }
        }
        return managers;
    }

    private static List<IProject> workspaceProjects()
    {
        List<IProject> result = new ArrayList<>();
        IV8ProjectManager projects = Global.getOsgiService(IV8ProjectManager.class);
        if (projects != null)
            for (IV8Project project : projects.getProjects())
            {
                IProject workspaceProject = project.getProject();
                if (workspaceProject != null && workspaceProject.isAccessible())
                    result.add(workspaceProject);
            }
        return result;
    }
}
