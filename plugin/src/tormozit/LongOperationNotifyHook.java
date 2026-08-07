package tormozit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.sun.jna.platform.win32.WinDef.HWND;

/**
 * Sticky-тост при успешном завершении важных длительных операций EDT, если
 * пользователь, скорее всего, не смотрит на workbench: окно без фокуса ОС
 * или нет событий мыши/клавиатуры ≥ 30 с. Белый список: загрузка конфигурации,
 * сравнение конфигураций, выгрузка конфигурации в файлы XML,
 * активация проектного контекста.
 * <p>Формат тоста: заголовок {@code EDT: <workspace>}, текст
 * {@code Завершено: <операция> [проект…] в HH:mm:ss за <длительность>}
 * (без даты — только время); без фокуса ОС — ссылка «Активировать окно».
 * Для активации проектного контекста имена проектов снимаются в {@code aboutToRun}
 * из очереди {@code DefaultContextsStartJob} (у Job нет поля проекта — batch).
 * <p>Загрузка и сравнение — только {@link IJobChangeListener} (в EDT 2026 загрузка
 * идёт фоновым Job без модального диалога). Выгрузка в XML — синхронный визард
 * «Экспорт»: fallback через обёртку «Готово».
 */
public final class LongOperationNotifyHook implements IStartup
{
    private static final String PATCHED_KEY_EXPORT = "tormozit.longOpNotifyExport"; //$NON-NLS-1$
    private static final String BTN_FINISH = "Готово"; //$NON-NLS-1$
    private static final String RADIO_DIR_SNIPPET = "каталог"; //$NON-NLS-1$
    private static final String ACTIVATE_LINK = "Активировать окно"; //$NON-NLS-1$
    private static final String TITLE_LOAD = "Загрузка конфигурации"; //$NON-NLS-1$
    private static final String TITLE_COMPARE = "Сравнение конфигураций"; //$NON-NLS-1$
    private static final String TITLE_EXPORT = "Выгрузка конфигурации в файлы XML"; //$NON-NLS-1$
    private static final String TITLE_PROJECT_CONTEXT =
        "Активация проектного контекста"; //$NON-NLS-1$

    private static final long DEDUP_MS = 5_000L;
    /** Показывать тост и при фокусе EDT, если ввода не было дольше этого порога. */
    private static final long IDLE_INPUT_MS = 30_000L;
    /** Короткие Job (< 3 с) не считаем «длительными» — тост не показываем. */
    private static final long MIN_DURATION_MS = 3_000L;

    private static final DateTimeFormatter COMPLETION_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    private static final OpMatcher[] MATCHERS = {
        new OpMatcher(TITLE_LOAD, name -> name.contains("Загрузка конфигурации") //$NON-NLS-1$
            || name.contains("Загрузка и обновление конфигураций") //$NON-NLS-1$
            || name.contains("Обновление конфигурации") //$NON-NLS-1$
            || name.contains("Обновление приложений")), //$NON-NLS-1$
        new OpMatcher(TITLE_COMPARE, name -> name.contains("Сравнение проектов") //$NON-NLS-1$
            || name.contains("Сравнение проекта") //$NON-NLS-1$
            || name.contains("Сравнение конфигураций") //$NON-NLS-1$
            || name.contains("Comparing project")), //$NON-NLS-1$
        new OpMatcher(TITLE_EXPORT, name -> name.contains("Экспорт проекта") //$NON-NLS-1$
            || name.contains("Выгрузка конфигурации") //$NON-NLS-1$
            || name.contains("Инкрементальный экспорт")), //$NON-NLS-1$
        new OpMatcher(TITLE_PROJECT_CONTEXT, name -> name.contains("Активация проектного контекста") //$NON-NLS-1$
            || name.contains("Activate project context")), //$NON-NLS-1$
    };

    private static final ConcurrentHashMap<String, Long> lastNotifyByTitle = new ConcurrentHashMap<>();
    /**
     * Старт Job (и имена проектов для активации контекста).
     * Старт пишем для любого Job — у сравнения имя появляется только к done;
     * запись только по match давала track=false и тост без «за …».
     * В map только текущие Job; remove в done.
     */
    private static final ConcurrentHashMap<Job, JobTrack> jobTracks = new ConcurrentHashMap<>();

    private static final AtomicBoolean jobListenerInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean inputFiltersInstalled = new AtomicBoolean(false);
    /** Время последнего KeyDown / MouseDown / MouseWheel на Display. */
    private static final AtomicLong lastInputMs = new AtomicLong(System.currentTimeMillis());
    /** Одновременно не более 3 sticky-тостов о завершении Job; 4-й вытесняет самый старый. */
    private static final int MAX_JOB_TOASTS = 3;
    private static final ArrayDeque<Shell> activeJobToasts = new ArrayDeque<>();

    private static final class JobTrack
    {
        final long startMs;
        /** Имена проектов через {@code ", "}, либо пусто. */
        final String projectNames;

        JobTrack(long startMs, String projectNames)
        {
            this.startMs = startMs;
            this.projectNames = projectNames != null ? projectNames : ""; //$NON-NLS-1$
        }
    }

    @Override
    public void earlyStartup()
    {
        // Listener Job — сразу, без ожидания UI-потока: иначе aboutToRun/scheduled
        // активации контекста при старте EDT уже прошли → done без track.
        ensureJobListener();
        Display.getDefault().asyncExec(() -> installUi(Display.getDefault()));
    }

    static void install(Display display)
    {
        installUi(display);
    }

    private static void installUi(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        ensureInputActivityFilters(display);

        // Только экспорт: модальный визард без надёжного Job-done.
        Listener shellListener = event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell) event.widget;
            if (shell.isDisposed())
                return;
            if (isCandidateExportShell(shell) && shell.getData(PATCHED_KEY_EXPORT) == null)
                scheduleExportFinishWrap(display, shell, 0);
        };
        display.addFilter(SWT.Activate, shellListener);
        display.addFilter(SWT.Show, shellListener);
    }

    private static void ensureInputActivityFilters(Display display)
    {
        if (!inputFiltersInstalled.compareAndSet(false, true))
            return;
        lastInputMs.set(System.currentTimeMillis());
        Listener inputListener = e -> lastInputMs.set(System.currentTimeMillis());
        display.addFilter(SWT.KeyDown, inputListener);
        display.addFilter(SWT.MouseDown, inputListener);
        display.addFilter(SWT.MouseWheel, inputListener);
    }

    private static boolean isCandidateExportShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return false;
        try
        {
            String title = shell.getText();
            return title != null && title.contains("Экспорт"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static void ensureJobListener()
    {
        if (!jobListenerInstalled.compareAndSet(false, true))
            return;
        // Job уже RUNNING/WAITING до установки listener — scheduled/aboutToRun не придут.
        for (Job existing : Job.getJobManager().find(null))
        {
            if (existing == null)
                continue;
            int state = existing.getState();
            if (state == Job.NONE)
                continue;
            rememberJobStart(existing, "seed"); //$NON-NLS-1$
        }
        Job.getJobManager().addJobChangeListener(new IJobChangeListener()
        {
            @Override public void awake(IJobChangeEvent event) {}
            @Override public void sleeping(IJobChangeEvent event) {}
            @Override public void running(IJobChangeEvent event) {}

            @Override
            public void scheduled(IJobChangeEvent event)
            {
                rememberJobStart(event.getJob(), "scheduled"); //$NON-NLS-1$
            }

            @Override
            public void aboutToRun(IJobChangeEvent event)
            {
                rememberJobStart(event.getJob(), "aboutToRun"); //$NON-NLS-1$
            }

            @Override
            public void done(IJobChangeEvent event)
            {
                Job job = event.getJob();
                IStatus result = event.getResult();
                JobTrack track = job != null ? jobTracks.remove(job) : null;
                if (job == null)
                    return;
                String operation = matchOperation(job);
                if (operation == null)
                    return;
                // OK и INFO — успех; WARNING/ERROR/CANCEL — не тостим.
                boolean ok = result != null && result.getSeverity() <= IStatus.INFO;
                long durationMs = track != null
                    ? Math.max(0L, System.currentTimeMillis() - track.startMs)
                    : -1L;
                String projectNames = track != null ? track.projectNames : ""; //$NON-NLS-1$
                Global.tempLog("long-op-notify", "done op=" + operation //$NON-NLS-1$
                    + " ok=" + ok //$NON-NLS-1$
                    + " sev=" + (result != null ? result.getSeverity() : -1) //$NON-NLS-1$
                    + " durMs=" + durationMs //$NON-NLS-1$
                    + " projects=[" + projectNames + "]" //$NON-NLS-1$
                    + " track=" + (track != null)); //$NON-NLS-1$
                if (!ok)
                    return;
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> notifyIfNeeded(operation, projectNames, durationMs));
            }
        });
    }

    /**
     * Фиксирует момент старта для любого Job; имена проектов — только для активации контекста.
     */
    private static void rememberJobStart(Job job, String phase)
    {
        if (job == null)
            return;
        long now = System.currentTimeMillis();
        jobTracks.putIfAbsent(job, new JobTrack(now, "")); //$NON-NLS-1$
        String operation = matchOperation(job);
        if (operation == null)
            return;
        Global.tempLog("long-op-notify", phase + " op=" + operation //$NON-NLS-1$
            + " job=" + job.getClass().getSimpleName() //$NON-NLS-1$
            + " name=[" + job.getName() + "]"); //$NON-NLS-1$
        if (!TITLE_PROJECT_CONTEXT.equals(operation))
            return;
        String projects = snapshotContextActivatorProjects(job);
        Global.tempLog("long-op-notify", phase + " projects=[" + projects + "]"); //$NON-NLS-1$
        if (projects.isEmpty())
            return;
        jobTracks.computeIfPresent(job, (j, prev) ->
            prev.projectNames.isEmpty() ? new JobTrack(prev.startMs, projects) : prev);
    }

    private static String matchOperation(Job job)
    {
        // Классы фоновой загрузки/обновления приложений (как DeployConfigurationFixHook).
        String className = job.getClass().getName();
        if (className.contains("DeployWithProgressOperation") //$NON-NLS-1$
            || className.contains("UpdateApplications") //$NON-NLS-1$
            || className.contains("CheckAndPublishApplications")) //$NON-NLS-1$
            return TITLE_LOAD;
        if (className.contains("DefaultContextsStartJob")) //$NON-NLS-1$
            return TITLE_PROJECT_CONTEXT;

        String jobName = job.getName();
        if (jobName == null || jobName.isEmpty())
            return null;
        for (OpMatcher matcher : MATCHERS)
        {
            if (matcher.matches.test(jobName))
                return matcher.title;
        }
        return null;
    }

    /**
     * Снимок имён из очереди {@code DtProjectResourceLifecycleBootstrap.projectContextToStart}
     * на aboutToRun: у {@code DefaultContextsStartJob} нет поля проекта (batch на несколько IProject).
     */
    private static String snapshotContextActivatorProjects(Job job)
    {
        try
        {
            Object bootstrap = Global.getField(job, "this$0"); //$NON-NLS-1$
            if (bootstrap == null)
                return ""; //$NON-NLS-1$
            Object queue = Global.getField(bootstrap, "projectContextToStart"); //$NON-NLS-1$
            if (!(queue instanceof Collection<?> latches))
                return ""; //$NON-NLS-1$
            // Без synchronized(queue): иначе возможен deadlock с DefaultContextsStartJob.run,
            // который тоже берёт monitor на эту же очередь.
            Object[] snapshot = latches.toArray();
            List<String> names = new ArrayList<>();
            for (Object latch : snapshot)
            {
                if (latch == null)
                    continue;
                Object request = Global.getField(latch, "projectContext"); //$NON-NLS-1$
                if (request == null)
                    continue;
                Object project = Global.invoke(request, "getWorkspaceProject"); //$NON-NLS-1$
                if (project instanceof IProject iProject)
                {
                    String name = iProject.getName();
                    if (name != null && !name.isEmpty() && !names.contains(name))
                        names.add(name);
                }
            }
            return names.isEmpty() ? "" : String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Global.tempLog("long-op-notify", "snapshot projects fail: " + e); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    private static void notifyIfNeeded(String operation, String projectNames, long durationMs)
    {
        // durationMs < 0 — старт не видели (не путать с коротким Job).
        if (durationMs >= 0L && durationMs < MIN_DURATION_MS)
        {
            Global.tempLog("long-op-notify", "skip short durMs=" + durationMs //$NON-NLS-1$
                + " op=" + operation); //$NON-NLS-1$
            return;
        }
        boolean focused = isEdtWorkbenchFocused();
        long idleMs = System.currentTimeMillis() - lastInputMs.get();
        if (!shouldNotifyUser())
        {
            Global.tempLog("long-op-notify", "skip user-busy focused=" + focused //$NON-NLS-1$
                + " idleMs=" + idleMs + " op=" + operation); //$NON-NLS-1$
            return;
        }
        long now = System.currentTimeMillis();
        String dedupKey = projectNames == null || projectNames.isEmpty()
            ? operation
            : operation + "|" + projectNames; //$NON-NLS-1$
        Long prev = lastNotifyByTitle.get(dedupKey);
        if (prev != null && now - prev < DEDUP_MS)
        {
            Global.tempLog("long-op-notify", "skip dedup key=" + dedupKey); //$NON-NLS-1$
            return;
        }
        lastNotifyByTitle.put(dedupKey, now);

        String workspace = resolveWorkspaceName();
        String toastTitle = workspace.isEmpty()
            ? "EDT" //$NON-NLS-1$
            : "EDT: " + workspace; //$NON-NLS-1$
        String completedAt = LocalDateTime.now().format(COMPLETION_FMT);
        StringBuilder message = new StringBuilder("Завершено: ").append(operation); //$NON-NLS-1$
        if (projectNames != null && !projectNames.isEmpty())
            message.append(' ').append(projectNames);
        message.append(" в ").append(completedAt); //$NON-NLS-1$
        if (durationMs >= 0L)
            message.append(" за ").append(formatDuration(durationMs)); //$NON-NLS-1$

        Global.tempLog("long-op-notify", "SHOW focused=" + focused //$NON-NLS-1$
            + " idleMs=" + idleMs + " msg=" + message); //$NON-NLS-1$

        evictJobToastsBeyondLimit();
        // Без фокуса ОС — ссылка активации; при фокусе (idle) — тот же текст без ссылки.
        Shell toast;
        if (!focused)
        {
            toast = ToastNotification.show(toastTitle, message.toString(), 0,
                LongOperationNotifyHook::activateEdtWindow, ACTIVATE_LINK);
        }
        else
        {
            toast = ToastNotification.show(toastTitle, message.toString(), 0);
        }
        registerJobToast(toast);
    }

    /** Перед показом нового: если уже 3 — закрыть самый старый. */
    private static void evictJobToastsBeyondLimit()
    {
        pruneDisposedJobToasts();
        while (activeJobToasts.size() >= MAX_JOB_TOASTS)
        {
            Shell oldest = activeJobToasts.pollFirst();
            if (oldest != null && !oldest.isDisposed())
                ToastNotification.close(oldest);
        }
    }

    private static void registerJobToast(Shell toast)
    {
        if (toast == null || toast.isDisposed())
            return;
        activeJobToasts.addLast(toast);
        toast.addDisposeListener(e -> activeJobToasts.remove(toast));
    }

    private static void pruneDisposedJobToasts()
    {
        activeJobToasts.removeIf(s -> s == null || s.isDisposed());
    }

    /** Человекочитаемая длительность: {@code 15с}, {@code 5м 30с}, {@code 1ч 5м}. */
    private static String formatDuration(long durationMs)
    {
        long totalSec = Math.max(0L, durationMs / 1000L);
        if (totalSec < 60)
            return totalSec + "с"; //$NON-NLS-1$
        long totalMin = totalSec / 60L;
        long sec = totalSec % 60L;
        if (totalMin < 60)
        {
            if (sec == 0)
                return totalMin + "м"; //$NON-NLS-1$
            return totalMin + "м " + sec + "с"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        long hours = totalMin / 60L;
        long min = totalMin % 60L;
        if (min == 0)
            return hours + "ч"; //$NON-NLS-1$
        return hours + "ч " + min + "м"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Имя каталога workspace (последний сегмент пути). */
    private static String resolveWorkspaceName()
    {
        try
        {
            IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
            if (location == null)
                return ""; //$NON-NLS-1$
            String name = location.lastSegment();
            return name != null ? name : ""; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private static void activateEdtWindow()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            if (windows.length > 0)
                window = windows[0];
        }
        if (window == null)
            return;
        Shell shell = window.getShell();
        if (shell == null || shell.isDisposed())
            return;
        if (shell.getMinimized())
            shell.setMinimized(false);
        HWND hwnd = WinWindowActivator.hwndFromShell(shell);
        if (hwnd != null)
            WinWindowActivator.activateWindowOnUiThread(hwnd);
        else
            shell.forceActive();
    }

    /**
     * Показывать тост, если workbench без фокуса ОС либо нет ввода мышью/клавиатурой
     * дольше {@link #IDLE_INPUT_MS}.
     */
    private static boolean shouldNotifyUser()
    {
        if (!isEdtWorkbenchFocused())
            return true;
        return System.currentTimeMillis() - lastInputMs.get() >= IDLE_INPUT_MS;
    }

    /**
     * Workbench в фокусе ОС: активный SWT-shell — окно workbench или его потомок
     * (диалог прогресса и т.п.). Иначе {@code getActiveShell() == null} или чужой shell.
     */
    private static boolean isEdtWorkbenchFocused()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return false;
        Shell active;
        try
        {
            active = display.getActiveShell();
        }
        catch (Exception e)
        {
            return false;
        }
        if (active == null || active.isDisposed())
            return false;
        if (!PlatformUI.isWorkbenchRunning())
            return false;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            Shell wb = window.getShell();
            if (wb == null || wb.isDisposed())
                continue;
            if (active == wb || isDescendantOf(active, wb))
                return true;
        }
        return false;
    }

    private static boolean isDescendantOf(Control control, Shell ancestor)
    {
        Control c = control;
        while (c != null)
        {
            if (c == ancestor)
                return true;
            c = c.getParent();
        }
        return false;
    }

    // --- Fallback: Finish визарда «Экспорт» (синхронный ProgressMonitorDialog) ---

    private static void scheduleExportFinishWrap(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY_EXPORT) != null)
            return;

        display.timerExec(attempt == 0 ? 250 : 100, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY_EXPORT) != null)
                return;

            // Якорь страницы экспорта конфигурации — радио «…каталог» + «Готово»
            // (как в ExportConfigurationXmlFixHook); без якоря не трогаем чужие «Экспорт».
            Button dirRadio = findButtonContaining(shell, RADIO_DIR_SNIPPET);
            Button finishButton = findButtonByText(shell, BTN_FINISH);
            if (dirRadio == null || finishButton == null)
            {
                if (attempt < 20)
                    scheduleExportFinishWrap(display, shell, attempt + 1);
                return;
            }

            shell.setData(PATCHED_KEY_EXPORT, Boolean.TRUE);
            // После ExportConfigurationXmlFixHook (timer 0/100), оборачиваем поверх.
            wrapFinishButton(finishButton, TITLE_EXPORT);
        });
    }

    private static void wrapFinishButton(Button finishButton, String toastTitle)
    {
        if (finishButton == null || finishButton.isDisposed())
            return;
        Listener[] original = finishButton.getListeners(SWT.Selection);
        for (Listener l : original)
            finishButton.removeListener(SWT.Selection, l);

        finishButton.addListener(SWT.Selection, event ->
        {
            long started = System.currentTimeMillis();
            for (Listener l : original)
                l.handleEvent(event);
            // Успешный performFinish закрывает визард и dispose'ит кнопку;
            // при ошибке/отмене shell остаётся — тост не нужен.
            if (finishButton.isDisposed())
                notifyIfNeeded(toastTitle, "", System.currentTimeMillis() - started); //$NON-NLS-1$
        });
    }

    private static String stripMnemonic(String text)
    {
        return text == null ? null : text.replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Button findButtonContaining(Control root, String snippet)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof Button)
        {
            Button b = (Button) root;
            String text = b.getText();
            if (text != null && text.contains(snippet))
                return b;
        }
        if (root instanceof Composite)
        {
            Control[] children;
            try
            {
                children = ((Composite) root).getChildren();
            }
            catch (Exception e)
            {
                return null;
            }
            for (Control child : children)
            {
                Button found = findButtonContaining(child, snippet);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Button findButtonByText(Control root, String text)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof Button)
        {
            Button b = (Button) root;
            if ((b.getStyle() & SWT.PUSH) != 0 && text.equals(stripMnemonic(b.getText())))
                return b;
        }
        if (root instanceof Composite)
        {
            Control[] children;
            try
            {
                children = ((Composite) root).getChildren();
            }
            catch (Exception e)
            {
                return null;
            }
            for (Control child : children)
            {
                Button found = findButtonByText(child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static final class OpMatcher
    {
        final String title;
        final Predicate<String> matches;

        OpMatcher(String title, Predicate<String> matches)
        {
            this.title = title;
            this.matches = matches;
        }
    }
}
