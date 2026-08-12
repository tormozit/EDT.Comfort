package tormozit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

/**
 * Sticky-тост при успешном завершении важных длительных операций EDT, если
 * пользователь, скорее всего, не смотрит на workbench: окно без фокуса ОС
 * или нет событий мыши/клавиатуры ≥ 30 с. Белый список: загрузка конфигурации,
 * сравнение конфигураций, объединение конфигураций, выгрузка конфигурации
 * в файлы XML, активация проектного контекста, переключение на ветку Git.
 * <p>Отдельный триггер — ошибки: если сама операция записала в журнал ошибок платформы
 * хотя бы одну запись уровня ERROR, тост показывается всегда (независимо
 * от фокуса, активности пользователя, длительности и статуса Job), содержит число
 * ошибок и ссылку «Открыть журнал ошибок».
 * <p>Отдельный триггер — модальное окно, ждущее решения пользователя во время
 * отслеживаемой операции: тост «Требуется решение пользователя: &lt;имя Job&gt;»
 * со ссылкой активации этого окна.
 * <p>Формат тоста: заголовок {@code EDT: <workspace>}, текст
 * {@code Завершено: <операция> [проект…] в HH:mm:ss за <длительность>}
 * (без даты — только время) и ссылка «Активировать окно».
 * Для активации проектного контекста имена проектов снимаются в {@code aboutToRun}
 * из очереди {@code DefaultContextsStartJob} (у Job нет поля проекта — batch).
 * <p>Загрузка и сравнение — только {@link IJobChangeListener} (в EDT 2026 загрузка
 * идёт фоновым Job без модального диалога). Выгрузка в XML — синхронный визард
 * «Экспорт»: fallback через обёртку «Готово».
 */
public final class LongOperationNotifyHook implements IStartup
{
    private static final String PATCHED_KEY_EXPORT = "tormozit.longOpNotifyExport"; //$NON-NLS-1$
    private static final String PATCHED_KEY_DECISION = "tormozit.longOpNotifyDecision"; //$NON-NLS-1$
    private static final String DECISION_PREFIX = "Требуется решение пользователя: "; //$NON-NLS-1$
    private static final String BTN_FINISH = "Готово"; //$NON-NLS-1$
    private static final String RADIO_DIR_SNIPPET = "каталог"; //$NON-NLS-1$
    private static final String ACTIVATE_LINK = "Активировать окно"; //$NON-NLS-1$
    private static final String ERROR_LOG_LINK = "Открыть журнал ошибок"; //$NON-NLS-1$
    /** Штатный Error Log (бандл {@code org.eclipse.ui.views.log}). */
    private static final String ERROR_LOG_VIEW_ID = "org.eclipse.pde.runtime.LogView"; //$NON-NLS-1$
    private static final String TITLE_LOAD = "Загрузка конфигурации"; //$NON-NLS-1$
    private static final String TITLE_COMPARE = "Сравнение конфигураций"; //$NON-NLS-1$
    private static final String TITLE_MERGE = "Объединение конфигураций"; //$NON-NLS-1$
    private static final String TITLE_CHECKOUT = "Переключение на ветку"; //$NON-NLS-1$
    private static final String TITLE_EXPORT = "Выгрузка конфигурации в файлы XML"; //$NON-NLS-1$
    private static final String TITLE_PROJECT_CONTEXT =
        "Активация проектного контекста"; //$NON-NLS-1$

    private static final long DEDUP_MS = 5_000L;
    /** Показывать тост и при фокусе EDT, если ввода не было дольше этого порога. */
    private static final long IDLE_INPUT_MS = 30_000L;
    /**
     * Короткие Job (&lt; 3 с) не считаем «длительными» — тост не показываем.
     * Только для Job, стартовавших без фокуса ОС у окна EDT: то, что запустил сам
     * пользователь из EDT, он ждёт — уведомляем независимо от длительности.
     */
    private static final long MIN_DURATION_MS = 3_000L;
    /** Пауза после done перед подсчётом ошибок журнала (записи могут опоздать). */
    private static final int ERROR_LOG_SETTLE_MS = 1_000;
    /** Допуск при сверке времени записи журнала с моментом её перехвата. */
    private static final long LOG_ENTRY_TIME_TOLERANCE_MS = 60_000L;
    /**
     * Поиск записи в дереве журнала: журнал открывается уже с актуальным списком,
     * поэтому ждём не дольше секунды.
     */
    private static final int LOG_ENTRY_LOOKUP_ATTEMPTS = 5;
    private static final int LOG_ENTRY_LOOKUP_DELAY_MS = 200;

    private static final DateTimeFormatter COMPLETION_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    private static final OpMatcher[] MATCHERS = {
        new OpMatcher(TITLE_LOAD, name -> name.contains("Загрузка конфигурации") //$NON-NLS-1$
            // DeployConfigurationJob: «Загружается конфигурация <проект>».
            || name.contains("Загружается конфигурация") //$NON-NLS-1$
            || name.contains("Загрузка и обновление конфигураций") //$NON-NLS-1$
            || name.contains("Обновление конфигурации") //$NON-NLS-1$
            || name.contains("Обновление приложений")), //$NON-NLS-1$
        new OpMatcher(TITLE_COMPARE, name -> name.contains("Сравнение проектов") //$NON-NLS-1$
            || name.contains("Сравнение проекта") //$NON-NLS-1$
            || name.contains("Сравнение конфигураций") //$NON-NLS-1$
            || name.contains("Comparing project")), //$NON-NLS-1$
        new OpMatcher(TITLE_MERGE, name -> name.equals("Объединение") //$NON-NLS-1$
            || name.contains("Объединение конфигураций") //$NON-NLS-1$
            || name.contains("Слияние") //$NON-NLS-1$
            || name.equals("Merging")), //$NON-NLS-1$
        // EGit BranchOperationUI$CheckoutJob: «Checking out <репозиторий> - <ветка>»
        // (перевода на русский в EGit нет).
        new OpMatcher(TITLE_CHECKOUT, name -> name.startsWith("Checking out")), //$NON-NLS-1$
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
    private static final AtomicBoolean errorLogListenerInstalled = new AtomicBoolean(false);
    /**
     * Поток выполнения Job → его {@link JobTrack}: нужен только для записей, попавших
     * в журнал уже после {@code done} (там {@code currentJob()} уже {@code null}).
     * Запись удаляется через {@link #ERROR_LOG_SETTLE_MS} после {@code done}.
     */
    private static final ConcurrentHashMap<Thread, JobTrack> tracksByThread = new ConcurrentHashMap<>();
    /**
     * Выполняющиеся сейчас отслеживаемые Job: имя Job → момент старта. Нужны, чтобы понять,
     * к какой операции относится всплывшее модальное окно, требующее решения пользователя.
     */
    private static final ConcurrentHashMap<Job, Long> runningTrackedJobs = new ConcurrentHashMap<>();
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
        /** Окно EDT было на переднем плане в момент старта Job. */
        final boolean edtForegroundAtStart;
        /** Ошибки журнала, записанные из потока именно этого Job. */
        final AtomicInteger errors;
        /** Первая из подсчитанных ошибок — её выделяем в журнале по ссылке в тосте. */
        final AtomicReference<ErrorRef> firstError;

        JobTrack(long startMs, String projectNames, boolean edtForegroundAtStart)
        {
            this(startMs, projectNames, edtForegroundAtStart, new AtomicInteger(),
                new AtomicReference<>());
        }

        JobTrack(long startMs, String projectNames, boolean edtForegroundAtStart,
            AtomicInteger errors, AtomicReference<ErrorRef> firstError)
        {
            this.startMs = startMs;
            this.projectNames = projectNames != null ? projectNames : ""; //$NON-NLS-1$
            this.edtForegroundAtStart = edtForegroundAtStart;
            this.errors = errors;
            this.firstError = firstError;
        }
    }

    /** Снимок записи журнала для поиска её строки в штатном журнале ошибок. */
    private static final class ErrorRef
    {
        final String message;
        final String pluginId;
        final long timeMs;

        ErrorRef(String message, String pluginId, long timeMs)
        {
            this.message = message != null ? message : ""; //$NON-NLS-1$
            this.pluginId = pluginId != null ? pluginId : ""; //$NON-NLS-1$
            this.timeMs = timeMs;
        }
    }

    @Override
    public void earlyStartup()
    {
        // Listener Job — сразу, без ожидания UI-потока: иначе aboutToRun/scheduled
        // активации контекста при старте EDT уже прошли → done без track.
        ensureJobListener();
        ensureErrorLogListener();
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
            if (shell.getData(PATCHED_KEY_DECISION) == null && isModalShell(shell))
                scheduleDecisionToast(display, shell, 0);
        };
        display.addFilter(SWT.Activate, shellListener);
        display.addFilter(SWT.Show, shellListener);
    }

    private static boolean isModalShell(Shell shell)
    {
        int modal = SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL;
        return (shell.getStyle() & modal) != 0;
    }

    /**
     * Тост о модальном окне, которое ждёт решения пользователя во время отслеживаемой
     * операции (например «Реорганизация информации» при обновлении конфигурации базы).
     * <p>Кнопки окна на {@code SWT.Show} ещё могут быть не созданы — несколько попыток.
     * Окно прогресса не в счёт: у него нет кнопок, кроме «Отмена».
     */
    private static void scheduleDecisionToast(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY_DECISION) != null)
            return;
        String jobName = activeTrackedJobName();
        if (jobName == null)
            return;

        display.timerExec(attempt == 0 ? 250 : 150, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY_DECISION) != null)
                return;
            if (!hasDecisionButtons(shell))
            {
                if (attempt < 10)
                    scheduleDecisionToast(display, shell, attempt + 1);
                return;
            }
            shell.setData(PATCHED_KEY_DECISION, Boolean.TRUE);
            String currentJobName = activeTrackedJobName();
            if (currentJobName == null)
                return;
            showDecisionToast(shell, currentJobName);
        });
    }

    /** Имя позже всех стартовавшего из выполняющихся отслеживаемых Job; {@code null} — таких нет. */
    private static String activeTrackedJobName()
    {
        Job latest = null;
        long latestStart = Long.MIN_VALUE;
        for (Map.Entry<Job, Long> entry : runningTrackedJobs.entrySet())
        {
            Job job = entry.getKey();
            if (job == null || job.getState() == Job.NONE)
                continue;
            Long startMs = entry.getValue();
            if (startMs != null && startMs > latestStart)
            {
                latestStart = startMs;
                latest = job;
            }
        }
        if (latest == null)
            return null;
        String name = latest.getName();
        return name != null && !name.isEmpty() ? name : null;
    }

    /** Есть кнопка, кроме «Отмена» — окно ждёт решения, а не просто показывает прогресс. */
    private static boolean hasDecisionButtons(Shell shell)
    {
        for (String text : collectPushButtonTexts(shell))
        {
            if (text.isEmpty() || "Отмена".equals(text) || "Cancel".equals(text)) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            return true;
        }
        return false;
    }

    private static List<String> collectPushButtonTexts(Control root)
    {
        List<String> result = new ArrayList<>();
        collectPushButtonTexts(root, result);
        return result;
    }

    private static void collectPushButtonTexts(Control root, List<String> result)
    {
        if (root == null || root.isDisposed())
            return;
        if (root instanceof Button button && (button.getStyle() & SWT.PUSH) != 0)
        {
            String text = stripMnemonic(button.getText());
            result.add(text != null ? text.trim() : ""); //$NON-NLS-1$
        }
        if (root instanceof Composite composite)
        {
            Control[] children;
            try
            {
                children = composite.getChildren();
            }
            catch (Exception e)
            {
                return;
            }
            for (Control child : children)
                collectPushButtonTexts(child, result);
        }
    }

    private static void showDecisionToast(Shell decisionShell, String jobName)
    {
        if (!shouldNotifyUser())
        {
            Global.tempLog("long-op-notify", "skip decision user-busy job=" + jobName); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        String workspace = resolveWorkspaceName();
        String toastTitle = workspace.isEmpty() ? "EDT" : "EDT: " + workspace; //$NON-NLS-1$ //$NON-NLS-2$
        Global.tempLog("long-op-notify", "SHOW decision job=" + jobName //$NON-NLS-1$ //$NON-NLS-2$
            + " shell=[" + decisionShell.getText() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        evictJobToastsBeyondLimit();
        // inputParentShell = само модальное окно: иначе ссылка тоста поверх modal не нажимается.
        Shell toast = ToastNotification.show(toastTitle, DECISION_PREFIX + jobName, 0,
            () -> activateDecisionShell(decisionShell), ACTIVATE_LINK, decisionShell);
        registerJobToast(toast);
        // Решение принято — окно закрылось, тост больше не нужен.
        if (toast != null && !toast.isDisposed())
            decisionShell.addDisposeListener(e -> ToastNotification.close(toast));
    }

    /** Активирует окно EDT и само модальное окно поверх него. */
    private static void activateDecisionShell(Shell decisionShell)
    {
        activateEdtWindow();
        if (decisionShell == null || decisionShell.isDisposed())
            return;
        if (decisionShell.getMinimized())
            decisionShell.setMinimized(false);
        HWND hwnd = WinWindowActivator.hwndFromShell(decisionShell);
        if (hwnd != null)
            WinWindowActivator.activateWindowOnUiThread(hwnd);
        else
            decisionShell.forceActive();
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

    /**
     * Считает записи уровня ERROR, попадающие в журнал ошибок платформы (штатный Error Log),
     * с привязкой к конкретному Job — параллельные Job не влияют друг на друга.
     * <p>Привязка: {@code IJobManager.currentJob()} в потоке, из которого пишется запись
     * (у {@link IStatus} ссылки на Job нет). Вложенные вызовы вроде
     * {@code MergeProcessJob.run → Reactor.executeTask → BmFqnAlreadyInUseException}
     * идут в том же потоке, поэтому {@code currentJob()} возвращает нужный Job.
     * Записи из потока Job уже после {@code done} ловятся через {@link #tracksByThread}.
     * <p>Ошибка, записанная из чужого пула потоков без {@code currentJob()},
     * не относится ни к какой операции и не учитывается.
     */
    private static void ensureErrorLogListener()
    {
        if (!errorLogListenerInstalled.compareAndSet(false, true))
            return;
        ILogListener listener = (status, plugin) ->
        {
            if (status == null || status.getSeverity() != IStatus.ERROR)
                return;
            countErrorForCurrentJob(status, plugin);
        };
        try
        {
            Platform.addLogListener(listener);
        }
        catch (Exception e)
        {
            Global.tempLog("long-op-notify", "addLogListener fail: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void countErrorForCurrentJob(IStatus status, String pluginId)
    {
        try
        {
            Job current = Job.getJobManager().currentJob();
            JobTrack track = current != null ? jobTracks.get(current) : null;
            if (track == null)
                track = tracksByThread.get(Thread.currentThread());
            if (track == null)
                return;
            track.errors.incrementAndGet();
            track.firstError.compareAndSet(null,
                new ErrorRef(status.getMessage(), pluginId, System.currentTimeMillis()));
        }
        catch (Exception e)
        {
            Global.tempLog("long-op-notify", "count error fail: " + e); //$NON-NLS-1$ //$NON-NLS-2$
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
            @Override
            public void running(IJobChangeEvent event)
            {
                bindJobThread(event.getJob());
            }

            @Override
            public void scheduled(IJobChangeEvent event)
            {
                rememberJobStart(event.getJob(), "scheduled"); //$NON-NLS-1$
            }

            @Override
            public void aboutToRun(IJobChangeEvent event)
            {
                rememberJobStart(event.getJob(), "aboutToRun"); //$NON-NLS-1$
                bindJobThread(event.getJob());
            }

            @Override
            public void done(IJobChangeEvent event)
            {
                Job job = event.getJob();
                IStatus result = event.getResult();
                JobTrack track = job != null ? jobTracks.remove(job) : null;
                if (job == null)
                    return;
                runningTrackedJobs.remove(job);
                String operation = matchOperation(job);
                if (operation == null)
                {
                    // Не отслеживаемая операция — привязку потока держать незачем.
                    unbindJobThreads(track);
                    return;
                }
                // OK и INFO — успех; WARNING/ERROR/CANCEL — не тостим (кроме ошибок в журнале).
                boolean ok = result != null && result.getSeverity() <= IStatus.INFO;
                long doneMs = System.currentTimeMillis();
                long durationMs = track != null ? Math.max(0L, doneMs - track.startMs) : -1L;
                String tracked = track != null ? track.projectNames : ""; //$NON-NLS-1$
                // Имя проекта у DeployConfigurationJob есть только в имени самого Job.
                final String projectNames = tracked.isEmpty() ? projectFromJobName(job) : tracked;
                Global.tempLog("long-op-notify", "done op=" + operation //$NON-NLS-1$
                    + " ok=" + ok //$NON-NLS-1$
                    + " fgAtStart=" + (track != null && track.edtForegroundAtStart) //$NON-NLS-1$
                    + " sev=" + (result != null ? result.getSeverity() : -1) //$NON-NLS-1$
                    + " durMs=" + durationMs //$NON-NLS-1$
                    + " projects=[" + projectNames + "]" //$NON-NLS-1$
                    + " track=" + (track != null)); //$NON-NLS-1$
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                JobTrack finished = track;
                // Пауза: последние ошибки Job могут попасть в журнал уже после done.
                // timerExec — только из UI-потока, done приходит из потока Job.
                display.asyncExec(() -> display.timerExec(ERROR_LOG_SETTLE_MS, () ->
                {
                    int errorCount = finished != null ? finished.errors.get() : 0;
                    ErrorRef firstError = finished != null ? finished.firstError.get() : null;
                    unbindJobThreads(finished);
                    Global.tempLog("long-op-notify", "errors op=" + operation //$NON-NLS-1$
                        + " count=" + errorCount); //$NON-NLS-1$
                    // Без ошибок — прежнее поведение (тост только при успехе).
                    if (errorCount == 0 && !ok)
                        return;
                    boolean startedInBackground = finished != null && !finished.edtForegroundAtStart;
                    notifyIfNeeded(operation, projectNames, durationMs, errorCount, firstError,
                        startedInBackground);
                }));
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
        jobTracks.putIfAbsent(job, new JobTrack(now, "", isEdtProcessForeground())); //$NON-NLS-1$
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
        jobTracks.computeIfPresent(job, (j, prev) -> prev.projectNames.isEmpty()
            ? new JobTrack(prev.startMs, projects, prev.edtForegroundAtStart, prev.errors,
                prev.firstError)
            : prev);
    }

    /**
     * Уточнение операции из имени Job: проект из «Загружается конфигурация &lt;проект&gt;»,
     * ветка из «Checking out &lt;репозиторий&gt; - &lt;ветка&gt;». Иначе пусто.
     */
    private static String projectFromJobName(Job job)
    {
        String name = job.getName();
        if (name == null)
            return ""; //$NON-NLS-1$
        String prefix = "Загружается конфигурация "; //$NON-NLS-1$
        int index = name.indexOf(prefix);
        if (index >= 0)
            return name.substring(index + prefix.length()).trim();
        if (name.startsWith("Checking out")) //$NON-NLS-1$
        {
            int separator = name.lastIndexOf(" - "); //$NON-NLS-1$
            if (separator >= 0)
                return shortBranchName(name.substring(separator + 3).trim());
            int to = name.lastIndexOf(" to "); //$NON-NLS-1$
            if (to >= 0)
                return shortBranchName(name.substring(to + 4).trim());
        }
        return ""; //$NON-NLS-1$
    }

    /** {@code refs/heads/main_8.5} → {@code main_8.5}. */
    private static String shortBranchName(String ref)
    {
        String prefix = "refs/heads/"; //$NON-NLS-1$
        return ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref;
    }

    /** Привязка потока выполнения к треку Job — для ошибок, залогированных после done. */
    private static void bindJobThread(Job job)
    {
        if (job == null)
            return;
        JobTrack track = jobTracks.get(job);
        if (track == null)
            return;
        if (matchOperation(job) != null)
            runningTrackedJobs.putIfAbsent(job, track.startMs);
        Thread thread = job.getThread();
        if (thread != null)
            tracksByThread.put(thread, track);
    }

    private static void unbindJobThreads(JobTrack track)
    {
        if (track == null)
            return;
        tracksByThread.values().remove(track);
    }

    private static String matchOperation(Job job)
    {
        // Классы фоновой загрузки/обновления приложений (как DeployConfigurationFixHook).
        String className = job.getClass().getName();
        if (className.contains("DeployWithProgressOperation") //$NON-NLS-1$
            || className.contains("UpdateApplications") //$NON-NLS-1$
            || className.contains("CheckAndPublishApplications") //$NON-NLS-1$
            // Обновление конфигурации базы (EDT 2026): infobases.deploy.DeployConfigurationJob,
            // имя Job — «Загружается конфигурация <проект>».
            || className.contains("DeployConfigurationJob")) //$NON-NLS-1$
            return TITLE_LOAD;
        if (className.contains("DefaultContextsStartJob")) //$NON-NLS-1$
            return TITLE_PROJECT_CONTEXT;
        // Объединение/слияние: com._1c.g5.v8.dt.internal.merge.MergeProcessJob,
        // имя Job — просто «Объединение» (Messages.MergeProcessJob_name).
        if (className.contains("MergeProcessJob")) //$NON-NLS-1$
            return TITLE_MERGE;
        if (className.contains("BranchOperationUI$CheckoutJob")) //$NON-NLS-1$
            return TITLE_CHECKOUT;

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
        // Визард «Экспорт»: операцию запускает сам пользователь из окна EDT.
        notifyIfNeeded(operation, projectNames, durationMs, 0, null, false);
    }

    /**
     * @param errorCount число ошибок журнала ошибок за время операции; {@code > 0} —
     *        тост показывается всегда (короткая операция и занятость пользователя
     *        не подавляют сообщение об ошибках)
     * @param firstError первая из подсчитанных ошибок — её строку выделяем в журнале
     *        по ссылке в тосте ({@code null} — просто открыть журнал)
     * @param startedInBackground операция стартовала, когда окно EDT не было на переднем
     *        плане (фоновая, не запущенная пользователем «здесь и сейчас») — только к таким
     *        применяется отсечка по длительности
     */
    private static void notifyIfNeeded(String operation, String projectNames, long durationMs,
        int errorCount, ErrorRef firstError, boolean startedInBackground)
    {
        boolean hasErrors = errorCount > 0;
        // durationMs < 0 — старт не видели (не путать с коротким Job).
        if (!hasErrors && startedInBackground && durationMs >= 0L && durationMs < MIN_DURATION_MS)
        {
            Global.tempLog("long-op-notify", "skip short durMs=" + durationMs //$NON-NLS-1$
                + " op=" + operation); //$NON-NLS-1$
            return;
        }
        boolean focused = isEdtWorkbenchFocused();
        long idleMs = System.currentTimeMillis() - lastInputMs.get();
        if (!hasErrors && !shouldNotifyUser())
        {
            Global.tempLog("long-op-notify", "skip user-busy focused=" + focused //$NON-NLS-1$
                + " idleMs=" + idleMs + " op=" + operation); //$NON-NLS-1$
            return;
        }
        long now = System.currentTimeMillis();
        String dedupKey = projectNames == null || projectNames.isEmpty()
            ? operation
            : operation + "|" + projectNames; //$NON-NLS-1$
        if (hasErrors)
            dedupKey = dedupKey + "|errors"; //$NON-NLS-1$
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
        StringBuilder message = new StringBuilder(hasErrors
            ? "Завершено с ошибками: " //$NON-NLS-1$
            : "Завершено: ").append(operation); //$NON-NLS-1$
        if (projectNames != null && !projectNames.isEmpty())
            message.append(' ').append(projectNames);
        message.append(" в ").append(completedAt); //$NON-NLS-1$
        if (durationMs >= 0L)
            message.append(" за ").append(formatDuration(durationMs)); //$NON-NLS-1$
        if (hasErrors)
        {
            message.append('\n')
                .append("Ошибок в журнале: ") //$NON-NLS-1$
                .append(errorCount);
        }

        Global.tempLog("long-op-notify", "SHOW focused=" + focused //$NON-NLS-1$
            + " idleMs=" + idleMs + " errors=" + errorCount //$NON-NLS-1$
            + " msg=" + message); //$NON-NLS-1$

        evictJobToastsBeyondLimit();
        // Есть ошибки — ссылка на журнал ошибок (она же активирует окно),
        // иначе — ссылка активации окна. Её даём всегда: определить «смотрит ли
        // пользователь на EDT» точно нельзя, а при активном окне ссылка безвредна.
        Shell toast;
        if (hasErrors)
        {
            toast = ToastNotification.show(toastTitle, message.toString(), 0,
                () -> openErrorLog(firstError), ERROR_LOG_LINK);
        }
        else
        {
            toast = ToastNotification.show(toastTitle, message.toString(), 0,
                LongOperationNotifyHook::activateEdtWindow, ACTIVATE_LINK);
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

    /**
     * Активирует окно EDT, открывает штатный журнал ошибок (Error Log) и выделяет в нём
     * строку первой подсчитанной ошибки.
     */
    private static void openErrorLog(ErrorRef firstError)
    {
        activateEdtWindow();
        if (!PlatformUI.isWorkbenchRunning())
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            if (windows.length == 0)
                return;
            window = windows[0];
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        try
        {
            IViewPart view = page.showView(ERROR_LOG_VIEW_ID);
            if (firstError == null || view == null)
                return;
            // Запись могла ещё не дойти до дерева журнала — пробуем несколько раз.
            selectErrorEntry(view, firstError, 0);
        }
        catch (Exception e)
        {
            Global.tempLog("long-op-notify", "showView error log fail: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Выделяет в дереве журнала ошибок строку записи {@code ref}.
     * <p>{@code LogView} отдаёт свой {@code TreeViewer} как selection provider площадки
     * (см. {@code createPartControl}), поэтому приватные поля не нужны. Элементы дерева —
     * {@code LogEntry}; сверяем сообщение, идентификатор бандла и близость времени.
     */
    private static void selectErrorEntry(IViewPart view, ErrorRef ref, int attempt)
    {
        Object provider = view.getViewSite() != null ? view.getViewSite().getSelectionProvider() : null;
        if (!(provider instanceof TreeViewer viewer) || viewer.getTree() == null
            || viewer.getTree().isDisposed())
        {
            Global.tempLog("long-op-notify", "log view selection provider=" + provider); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Object input = viewer.getInput() != null ? viewer.getInput() : view;
        Object match = findLogEntry(viewer, input, ref, 0);
        if (match == null)
        {
            if (attempt < LOG_ENTRY_LOOKUP_ATTEMPTS)
            {
                viewer.getTree().getDisplay().timerExec(LOG_ENTRY_LOOKUP_DELAY_MS,
                    () -> selectErrorEntry(view, ref, attempt + 1));
                return;
            }
            Global.tempLog("long-op-notify", "log entry not found msg=[" //$NON-NLS-1$ //$NON-NLS-2$
                + ref.message + "]"); //$NON-NLS-1$
            return;
        }
        viewer.setSelection(new StructuredSelection(match), true);
        viewer.getTree().setFocus();
    }

    /** Обход модели дерева журнала через его content provider (глубина ограничена). */
    private static Object findLogEntry(TreeViewer viewer, Object parent, ErrorRef ref, int depth)
    {
        if (parent == null || depth > 4)
            return null;
        Object[] children;
        try
        {
            children = depth == 0
                ? ((ITreeContentProvider)viewer.getContentProvider()).getElements(parent)
                : ((ITreeContentProvider)viewer.getContentProvider()).getChildren(parent);
        }
        catch (Exception e)
        {
            return null;
        }
        if (children == null)
            return null;
        for (Object child : children)
        {
            if (matchesLogEntry(child, ref))
                return child;
            Object found = findLogEntry(viewer, child, ref, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    /** {@code LogEntry} без прямой зависимости на бандл {@code org.eclipse.ui.views.log}. */
    private static boolean matchesLogEntry(Object element, ErrorRef ref)
    {
        if (element == null || !element.getClass().getName().endsWith(".LogEntry")) //$NON-NLS-1$
            return false;
        Object message = Global.invoke(element, "getMessage"); //$NON-NLS-1$
        if (!(message instanceof String text) || !ref.message.equals(text))
            return false;
        // Идентификатор бандла НЕ сверяем: у ILogListener это бандл-источник вызова
        // (часто org.eclipse.core.runtime), а у LogEntry — IStatus.getPlugin(); они расходятся.
        Object date = Global.invoke(element, "getDate"); //$NON-NLS-1$
        // Одинаковые сообщения могли повторяться — берём запись рядом по времени.
        if (date instanceof Date entryDate)
            return Math.abs(entryDate.getTime() - ref.timeMs) <= LOG_ENTRY_TIME_TOLERANCE_MS;
        return true;
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
     * Окно EDT (любое окно нашего процесса) на переднем плане.
     * <p>Отдельный метод от {@link #isEdtWorkbenchFocused()}: вызывается из потока Job,
     * где обращаться к SWT-виджетам нельзя, поэтому только Win32 — окно переднего плана
     * и его PID. Диалог, из которого пользователь запустил операцию, тоже считается EDT.
     */
    private static boolean isEdtProcessForeground()
    {
        if (!WinWindowActivator.isWindows())
            return true;
        try
        {
            HWND foreground = User32.INSTANCE.GetForegroundWindow();
            if (foreground == null)
                return false;
            return WinWindowActivator.isProcessWindow(foreground, (int)ProcessHandle.current().pid());
        }
        catch (Throwable e)
        {
            Global.tempLog("long-op-notify", "process foreground check fail: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
    }

    /**
     * Workbench в фокусе ОС. На Windows спрашиваем сам ОС ({@code GetForegroundWindow}):
     * {@code Display.getActiveShell()} возвращает shell и тогда, когда приложение не на
     * переднем плане, из-за чего операция считалась «просмотренной» пользователем.
     */
    private static boolean isEdtWorkbenchFocused()
    {
        if (!WinWindowActivator.isWindows())
            return isEdtWorkbenchFocusedBySwt();
        try
        {
            HWND foreground = User32.INSTANCE.GetForegroundWindow();
            if (foreground != null)
                return WinWindowActivator.isEdtForeground(foreground);
        }
        catch (Throwable e)
        {
            Global.tempLog("long-op-notify", "foreground check fail: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return isEdtWorkbenchFocusedBySwt();
    }

    /** Резерв, если обращение к Win32 недоступно. */
    private static boolean isEdtWorkbenchFocusedBySwt()
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
