package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.IDerivedDataStatusListener;
import com._1c.g5.v8.derived.pipeline.PipelineSegmentStatus;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

/**
 * Свежесть полнотекстового индекса EDT ({@code TextSearchIndex}) на вкладке «Поиск в
 * конфигурациях» — других вкладок окна «Поиск», которые им пользуются, нет. Индекс считается
 * производными данными проекта: сегменты {@code FTS_INDEXING_SEGMENT}/{@code FTS_CLEANER_SEGMENT};
 * их остаток работы — из {@code IDerivedDataManager} (статус и слушатель статуса). Пока индекс
 * обновляется — крутилка как в панели «Проблемы конфигурации».
 */
final class IndexFreshness
{
    private static final Set<String> SEGMENTS = Set.of("FTS_INDEXING_SEGMENT", "FTS_CLEANER_SEGMENT"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final int POLL_MS = 300;
    private static final String TOOLTIP = "Поиск в конфигурации ищет не в самих модулях и свойствах, а в полнотекстовом"
        + " индексе EDT. Индекс обновляется в фоне после каждого изменения. Пока он обновляется, поиск может не найти"
        + " недавно добавленный текст, найти уже изменённый или показать вхождение по старому месту в тексте."
        + " Чтобы результаты были точными, дождитесь окончания обновления и повторите поиск";

    private static final String REFACTORING_TOOLTIP = "Вхождения в комментариях, строковых литералах и текстах в модели"
        + " EDT ищет по полнотекстовому индексу. Пока индекс обновляется (например, после правки модуля), часть"
        + " вхождений может не найтись. Чтобы список был полным, дождитесь окончания обновления и повторите"
        + " рефакторинг";
    /** Данные окна мастера: индекс отставал в какой-то момент с открытия окна. */
    private static final String STALE_SEEN_KEY = "tormozit.indexFreshnessStaleSeen"; //$NON-NLS-1$
    private static final String WATCHED_KEY = "tormozit.indexFreshnessWatched"; //$NON-NLS-1$
    private static final String REFACTORING_DIAG_TOPIC = "fts-freshness-refactoring"; // agent log — временно //$NON-NLS-1$
    private static final int REFACTORING_DIAG_POLL_MS = 200; // agent log — временно

    private final Label label;
    /** Окно мастера рефакторинга; {@code null} — вкладка поиска по конфигурации. */
    private final Shell shell;
    private final String tooltip;
    private final Map<IDerivedDataManager, IDerivedDataStatusListener> listeners = new HashMap<>();
    private WaitSpinner spinner;
    private boolean refreshPending;

    private IndexFreshness(Label label, Shell shell, String tooltip)
    {
        this.label = label;
        this.shell = shell;
        this.tooltip = tooltip;
    }

    /**
     * Мастер рефакторинга ищет вхождения в момент открытия, а страница предпросмотра появляется
     * позже: индекс мог отставать только при поиске и успеть обновиться. Поэтому отставание
     * запоминается на окне с первого показа, а подпись на странице читает этот признак.
     */
    static void watch(Shell shell)
    {
        if (shell == null || shell.isDisposed() || shell.getData(WATCHED_KEY) != null)
            return;
        shell.setData(WATCHED_KEY, Boolean.TRUE);
        Display display = shell.getDisplay();
        // #region agent log — временно (рефакторинг: «найдено 1 из 3»): правки .bsl с метками времени
        Global.tempLog(REFACTORING_DIAG_TOPIC, "watchStart shell=" + shell.getText()); //$NON-NLS-1$
        org.eclipse.core.resources.IResourceChangeListener files = event ->
        {
            if (event.getDelta() == null)
                return;
            try
            {
                event.getDelta().accept(delta ->
                {
                    if (delta.getResource() instanceof IFile file && "bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                        Global.tempLog(REFACTORING_DIAG_TOPIC, "fileChanged " + file.getFullPath() + " kind=" //$NON-NLS-1$ //$NON-NLS-2$
                            + delta.getKind() + " flags=" + delta.getFlags()); //$NON-NLS-1$
                    return true;
                });
            }
            catch (Exception e)
            {
                Global.tempLog(REFACTORING_DIAG_TOPIC, "fileChanged error " + e); //$NON-NLS-1$
            }
        };
        org.eclipse.core.resources.ResourcesPlugin.getWorkspace().addResourceChangeListener(files,
            org.eclipse.core.resources.IResourceChangeEvent.POST_CHANGE);
        shell.addDisposeListener(e -> org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .removeResourceChangeListener(files));
        // #endregion
        display.timerExec(0, new Runnable()
        {
            boolean staleFlag;

            @Override
            public void run()
            {
                if (shell.isDisposed())
                {
                    // #region agent log — временно (рефакторинг: «найдено 1 из 3», индекс отставал?)
                    Global.tempLog(REFACTORING_DIAG_TOPIC, "shellDisposed staleSeen=" + staleFlag); //$NON-NLS-1$
                    // #endregion
                    return;
                }
                List<IDerivedDataManager> all = managers();
                int[] remaining = new int[1];
                boolean busy = busy(all, remaining);
                if (busy)
                    shell.setData(STALE_SEEN_KEY, Boolean.TRUE);
                staleFlag = shell.getData(STALE_SEEN_KEY) != null;
                // #region agent log — временно: безусловно, каждый опрос (порог — данные, не условие)
                StringBuilder line = new StringBuilder("watch busy=").append(busy) //$NON-NLS-1$
                    .append(" remaining=").append(remaining[0]).append(" staleSeen=").append(staleFlag); //$NON-NLS-1$ //$NON-NLS-2$
                for (IDerivedDataManager manager : all)
                    line.append(" | ").append(PROJECTS.get(manager)) //$NON-NLS-1$
                        .append(" ftsComputed=").append(safe(() -> manager.isComputed("FTS_INDEXING_SEGMENT"))) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(" cleanerComputed=").append(safe(() -> manager.isComputed("FTS_CLEANER_SEGMENT"))) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(' ').append(describe(manager.getDerivedDataStatus()));
                Global.tempLog(REFACTORING_DIAG_TOPIC, line.toString());
                // #endregion
                display.timerExec(REFACTORING_DIAG_POLL_MS, this);
            }
        });
    }

    /** Подпись состояния индекса в нижней строке страницы предпросмотра рефакторинга. */
    static void installInRefactoring(Composite root, Shell shell)
    {
        if (root == null || root.isDisposed() || !(root.getLayout() instanceof GridLayout grid))
            return;
        install(root, grid, shell, REFACTORING_TOOLTIP);
    }

    static void install(Object page)
    {
        if (!(Global.invoke(page, "getControl") instanceof Composite root) || root.isDisposed() //$NON-NLS-1$
            || !(root.getLayout() instanceof GridLayout grid))
            return;
        install(root, grid, null, TOOLTIP);
    }

    private static void install(Composite root, GridLayout grid, Shell shell, String tooltip)
    {
        Label label = new Label(root, SWT.NONE);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, grid.numColumns, 1));
        label.setToolTipText(TooltipText.wrap(label, tooltip + Global.pluginSignForTooltip()));
        IndexFreshness freshness = new IndexFreshness(label, shell, tooltip);
        freshness.listen();
        label.addDisposeListener(e -> freshness.unlisten());
        freshness.startPolling();
        freshness.startDiagnostics();
    }

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
                // #region agent log — временно (issue #800)
                Global.tempLog(DIAG_TOPIC, "statusChanged " + describe(status)); //$NON-NLS-1$
                // #endregion
                scheduleRefresh();
            };
            manager.addStatusListener(listener);
            listeners.put(manager, listener);
        }
    }

    // #region agent log — временно (issue #800): какие сигналы горят, пока индекс отстаёт от файлов.
    private static final String DIAG_TOPIC = "fts-freshness-800"; //$NON-NLS-1$
    private static final int DIAG_POLL_MS = 200;

    private void startDiagnostics()
    {
        org.eclipse.core.resources.IResourceChangeListener files = event ->
        {
            if (event.getDelta() == null)
                return;
            try
            {
                event.getDelta().accept(delta ->
                {
                    if (delta.getResource() instanceof IFile file && "bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                        Global.tempLog(DIAG_TOPIC, "fileChanged " + file.getFullPath() + " kind=" + delta.getKind() //$NON-NLS-1$ //$NON-NLS-2$
                            + " flags=" + delta.getFlags()); //$NON-NLS-1$
                    return true;
                });
            }
            catch (Exception e)
            {
                Global.tempLog(DIAG_TOPIC, "fileChanged error " + e); //$NON-NLS-1$
            }
        };
        org.eclipse.core.resources.ResourcesPlugin.getWorkspace().addResourceChangeListener(files,
            org.eclipse.core.resources.IResourceChangeEvent.POST_CHANGE);
        label.addDisposeListener(e -> org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .removeResourceChangeListener(files));
        Display display = label.getDisplay();
        display.timerExec(0, new Runnable()
        {
            @Override
            public void run()
            {
                if (label.isDisposed())
                    return;
                StringBuilder line = new StringBuilder("poll label=[").append(label.getText()).append(']'); //$NON-NLS-1$
                Object indexManager = Global.getOsgiService(
                    com._1c.g5.v8.dt.search.core.text.ITextSearchIndexProvider.class);
                Object entries = Global.getField(indexManager, "projectEntries"); //$NON-NLS-1$
                if (!(entries instanceof Map<?, ?>))
                    line.append(" entries=null indexManager=") //$NON-NLS-1$
                        .append(indexManager != null ? indexManager.getClass().getName() : "null"); //$NON-NLS-1$
                for (Map.Entry<IDerivedDataManager, IProject> project : PROJECTS.entrySet())
                {
                    IDerivedDataManager manager = project.getKey();
                    line.append(" | ").append(project.getValue().getName()) //$NON-NLS-1$
                        .append(" idle=").append(safe(manager::isIdle)) //$NON-NLS-1$
                        .append(" ftsComputed=").append(safe(() -> manager.isComputed("FTS_INDEXING_SEGMENT"))) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(" cleanerComputed=").append(safe(() -> manager.isComputed("FTS_CLEANER_SEGMENT"))) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(' ').append(describe(manager.getDerivedDataStatus()));
                    Object entry = entries instanceof Map<?, ?> map ? map.get(project.getValue()) : null;
                    Object batch = Global.getField(entry, "activeBatch"); //$NON-NLS-1$
                    Object postponed = Global.getField(entry, "postponedFilesBatchs"); //$NON-NLS-1$
                    line.append(" entry=").append(entry != null) //$NON-NLS-1$
                        .append(" activeBatch=").append(batch == null ? "null" //$NON-NLS-1$ //$NON-NLS-2$
                            : Global.getField(batch, "addedFiles") + "/" + Global.getField(batch, "removedFiles")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        .append(" postponed=").append(postponed instanceof java.util.Collection<?> list ? list.size() : -1) //$NON-NLS-1$
                        .append(" syncAfterSnapshot=").append(Global.getField(entry, "synchronizingAfterSnapshotRestoration")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                Global.tempLog(DIAG_TOPIC, line.toString());
                display.timerExec(DIAG_POLL_MS, this);
            }
        });
    }

    private static String safe(java.util.function.BooleanSupplier value)
    {
        try
        {
            return String.valueOf(value.getAsBoolean());
        }
        catch (RuntimeException e)
        {
            return "error:" + e; //$NON-NLS-1$
        }
    }

    private static String describe(DerivedDataStatus status)
    {
        if (status == null)
            return "status=null"; //$NON-NLS-1$
        StringBuilder text = new StringBuilder("pipeline=").append(status.getPipelineStatus()) //$NON-NLS-1$
            .append(" stage=").append(status.getActiveStage()) //$NON-NLS-1$
            .append(" modelSync=").append(status.isModelSyncActive()).append(" active={"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, PipelineSegmentStatus> active = status.getActivePipelineSegments();
        if (active != null)
            active.forEach((id, segment) -> text.append(id).append(':')
                .append(segment != null ? segment.getRemainingUnitsOfWork() + "/" + segment.getTotalUnitsOfWork() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                .append(' '));
        return text.append('}').toString();
    }

    /** Проект каждого менеджера — для подписи строк диагностики. */
    private static final Map<IDerivedDataManager, IProject> PROJECTS = new java.util.concurrent.ConcurrentHashMap<>();
    // #endregion

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
        int[] remainingBox = new int[1];
        boolean busy = busy(listeners.keySet(), remainingBox);
        int remaining = remainingBox[0];
        boolean refactoring = shell != null;
        if (refactoring && busy && !shell.isDisposed())
            shell.setData(STALE_SEEN_KEY, Boolean.TRUE);
        boolean staleSeen = refactoring && !shell.isDisposed() && shell.getData(STALE_SEEN_KEY) != null;
        String text = busy ? (remaining > 0 ? "Полнотекстовый индекс обновляется, осталось: " + remaining
                : "Полнотекстовый индекс обновляется")
                + (refactoring ? ": вхождения в комментариях и текстах могут быть найдены не все" : "")
            : staleSeen ? "Индекс отставал при поиске вхождений: в комментариях и текстах могли найтись не все."
                + " Закройте окно и повторите рефакторинг"
            : "Полнотекстовый индекс актуален";
        if (!text.equals(label.getText()))
        {
            label.setText(text);
            label.getParent().layout(true, true);
        }
        if (busy && (spinner == null || spinner.isDisposed()))
            spinner = WaitSpinner.attach(label, tooltip);
        if (spinner != null)
            spinner.setActive(busy);
    }

    /**
     * Индекс отстаёт, если сегмент не досчитан или у него есть остаток работы.
     * Отставание после сохранения модуля: сегмент не досчитан, а карта активных сегментов при этом
     * пуста и событий статуса нет (лог 17.10.2026, ~17 с).
     */
    private static boolean busy(Iterable<IDerivedDataManager> managers, int[] remaining)
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
                    remaining[0] += segment.getValue().getRemainingUnitsOfWork();
                }
        }
        return busy;
    }

    private static List<IDerivedDataManager> managers()
    {
        List<IDerivedDataManager> managers = new ArrayList<>();
        IV8ProjectManager projects = Global.getOsgiService(IV8ProjectManager.class);
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        if (projects == null || provider == null)
            return managers;
        for (IV8Project project : projects.getProjects())
        {
            IProject workspaceProject = project.getProject();
            IDerivedDataManager manager = workspaceProject != null && workspaceProject.isAccessible()
                ? provider.get(workspaceProject) : null;
            if (manager != null && !managers.contains(manager))
            {
                managers.add(manager);
                PROJECTS.put(manager, workspaceProject); // agent log — временно (issue #800)
            }
        }
        return managers;
    }
}
