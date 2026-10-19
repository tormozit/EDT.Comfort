package tormozit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IDocumentProviderExtension;
import org.eclipse.ui.texteditor.IElementStateListener;
import org.eclipse.ui.texteditor.IElementStateListenerExtension;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Штатная EDT в {@code CustomXtextDocumentLocker.waitUpdatingDataModel} на UI-потоке
 * спит до 5 с ({@code Thread.sleep(25)}), пока не сменится parse result — в том числе
 * при Ctrl+S из {@code BslBreakpointMarkerUpdater.updateMarker}. Комфорт на потоке UI
 * этот цикл пропускает: сохранение не блокирует интерфейс.
 *
 * <p>Вплетение в блокировщик — в раннем бандле ({@code tormozit.early.Activator}, цель
 * {@code DOC_LOCKER}): класс грузится раньше активации основного бандла, а агента перезагрузки
 * классов в EDT нет, поэтому вплетение отсюда не срабатывало ни разу (временный лог 15.10.2026
 * 10:33). Здесь — только функция пропуска ожидания ({@link #PROP_SKIP_WAIT}); ранний бандл
 * зовёт её через {@code System.getProperties}.
 */
public final class BslXtextDocumentHook implements IStartup
{
    /** Совпадает с {@code EarlyWeaving.PROP_SKIP_WAIT} раннего бандла. */
    static final String PROP_SKIP_WAIT = "tormozit.bslXtextDoc.skipWaitUpdatingDataModel"; //$NON-NLS-1$
    /** Совпадает с {@code EarlyWeaving.PROP_ASSIST_READ_DEPTH}: глубина вплетённого чтения на потоке. */
    private static final String PROP_ASSIST_READ_DEPTH = "tormozit.bslXtextDoc.assistReadDepth"; //$NON-NLS-1$
    /** Совпадает с {@code EarlyWeaving.PROP_ASSIST_READ_OBSERVER}. */
    private static final String PROP_ASSIST_READ_OBSERVER = "tormozit.bslXtextDoc.assistReadObserver"; //$NON-NLS-1$
    /** Совпадает с {@code EarlyWeaving.PROP_DOC_LOCKER_PATCH}: итог вплетения в блокировщик. */
    private static final String PROP_DOC_LOCKER_PATCH = "tormozit.bslXtextDoc.lockerPatch"; //$NON-NLS-1$
    private static final String CMD_SAVE = "org.eclipse.ui.file.save"; //$NON-NLS-1$

    private static final AtomicInteger SAVE_SKIP = new AtomicInteger();
    private static final AtomicInteger SAVE_WAIT = new AtomicInteger();
    private static final ThreadLocal<Long> SAVE_STARTED_MS = new ThreadLocal<>();
    private static final ThreadLocal<String> SAVE_MODULE = new ThreadLocal<>();
    private static volatile boolean saveActive;
    private static volatile long saveUiUntilMs;
    private static final AtomicBoolean uiInstalled = new AtomicBoolean();

    /**
     * Наблюдатель раннего бандла: ожидание монитора ресурса и блокировки чтения во вплетённом
     * {@code readOnlyForContentAssist} (от 5 мс) и ошибки рефлексии.
     */
    private static void observeAssistRead(String event, Long ms)
    {
        if (event.startsWith("lockDiag.")) //$NON-NLS-1$
        {
            return;
        }
        SmartContentAssistProcessor.uiBlockLog("modelSync.readLock", //$NON-NLS-1$
            event + " waitMs=" + ms + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Итог вплетения раннего бандла: {@code woven wait=1 read=1}; {@code null} — класс через хук не проходил. */
    private static String lockerPatch()
    {
        return System.getProperty(PROP_DOC_LOCKER_PATCH);
    }

    /**
     * Как можно раньше, из {@link Activator#start}: функция пропуска ожидания должна стоять до
     * первого сохранения.
     */
    public static void install()
    {
        System.getProperties().put(PROP_SKIP_WAIT,
            (BooleanSupplier) BslXtextDocumentHook::skipWaitUpdatingDataModel);
        // #region agent log
        System.getProperties().put(PROP_ASSIST_READ_OBSERVER,
            (BiConsumer<String, Long>) BslXtextDocumentHook::observeAssistRead);
        // #endregion
    }

    @Override
    public void earlyStartup()
    {
        install();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(BslXtextDocumentHook::installUi);
    }

    /**
     * Поток фонового расчёта автодополнения. Ctrl+Space считает список на UI и это
     * ожидание пропускает; фоновый расчёт того же списка должен вести себя так же,
     * иначе он отсиживает до 1000 мс там, где эталон не ждёт ничего.
     */
    private static final ThreadLocal<Boolean> ASSIST_BACKGROUND = new ThreadLocal<>();

    /** Метка на время фонового расчёта автодополнения. Снимать в {@code finally}. */
    public static void markAssistBackground(boolean on)
    {
        if (on)
            ASSIST_BACKGROUND.set(Boolean.TRUE);
        else
            ASSIST_BACKGROUND.remove();
    }

    /**
     * Вызов из вплетённого {@code waitUpdatingDataModel}: {@code true} — сразу выйти, не ждать
     * смены parse result.
     */
    public static boolean skipWaitUpdatingDataModel()
    {
        // Внутри вплетённого readOnlyForContentAssist разбор ждёт наш же монитор — смена
        // parse result до выхода невозможна, ждать её (до 1 с) бессмысленно.
        boolean ui = Display.getCurrent() != null
            || Boolean.TRUE.equals(ASSIST_BACKGROUND.get())
            || isInsideAssistRead();
        if (saveActive && SaveDebug.isEnabled())
        {
            int n = ui ? SAVE_SKIP.incrementAndGet() : SAVE_WAIT.incrementAndGet();
            if (n == 1)
            {
                SaveDebug.step("waitUpdatingDataModel", //$NON-NLS-1$
                    (ui ? "skip" : "wait") //$NON-NLS-1$ //$NON-NLS-2$
                        + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
            }
        }
        return ui;
    }

    /** Поток сейчас внутри вплетённого {@code readOnlyForContentAssist} (стек — в раннем бандле). */
    private static boolean isInsideAssistRead()
    {
        return System.getProperties().get(PROP_ASSIST_READ_DEPTH) instanceof IntSupplier depth
            && depth.getAsInt() > 0;
    }

    private static void installUi()
    {
        if (!uiInstalled.compareAndSet(false, true))
            return;
        installSaveLog();
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, event ->
        {
            if (!saveActive && System.currentTimeMillis() > saveUiUntilMs)
                return;
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            String title = shell.getText();
            if (title == null || title.isBlank())
                return;
            boolean modal = (shell.getStyle() & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL
                | SWT.SYSTEM_MODAL)) != 0;
            if (!modal && !looksLikeJobsDialog(title))
                return;
            SaveDebug.step("modal", (modal ? "modal " : "") + title //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " jobs=" + runningJobsBrief()); //$NON-NLS-1$
        });
    }

    private static boolean looksLikeJobsDialog(String title)
    {
        String t = title.toLowerCase();
        return t.contains("ожид") //$NON-NLS-1$
            || t.contains("wait") //$NON-NLS-1$
            || t.contains("фонов") //$NON-NLS-1$
            || t.contains("операция"); //$NON-NLS-1$
    }

    private static String runningJobsBrief()
    {
        Job[] jobs = Job.getJobManager().find(null);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Job job : jobs)
        {
            if (job.getState() != Job.RUNNING)
                continue;
            n++;
            if (n > 6)
            {
                sb.append(" …"); //$NON-NLS-1$
                break;
            }
            if (sb.length() > 0)
                sb.append("; "); //$NON-NLS-1$
            sb.append(job.getName());
        }
        return n == 0 ? "none" : n + " " + sb; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void installSaveLog()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commands == null)
            return;
        commands.addExecutionListener(SAVE_COMMANDS);
    }

    private static final IExecutionListener SAVE_COMMANDS = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!CMD_SAVE.equals(commandId))
                return;
            SAVE_STARTED_MS.remove();
            SAVE_MODULE.remove();
            saveActive = false;
            SAVE_SKIP.set(0);
            SAVE_WAIT.set(0);
            IEditorPart editor = resolveEditor(event);
            BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
            StateValidationAfterSave.beforeSave(bsl);
            if (!SaveDebug.isEnabled())
                return;
            if (bsl == null)
                return;
            String module = moduleLabel(bsl);
            SAVE_STARTED_MS.set(Long.valueOf(System.currentTimeMillis()));
            SAVE_MODULE.set(module);
            saveActive = true;
            saveUiUntilMs = Long.MAX_VALUE;
            boolean dirty = editor != null && editor.isDirty();
            SaveDebug.step("save", module + " dirty=" + dirty //$NON-NLS-1$ //$NON-NLS-2$
                + " lockerPatch=" + lockerPatch()); //$NON-NLS-1$
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            endSave(commandId, "ok"); //$NON-NLS-1$
            if (CMD_SAVE.equals(commandId))
                StateValidationAfterSave.afterSave();
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            endSave(commandId, "fail"); //$NON-NLS-1$
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            endSave(commandId, "notHandled"); //$NON-NLS-1$
        }
    };

    private static void endSave(String commandId, String outcome)
    {
        if (!CMD_SAVE.equals(commandId))
            return;
        Long started = SAVE_STARTED_MS.get();
        String module = SAVE_MODULE.get();
        SAVE_STARTED_MS.remove();
        SAVE_MODULE.remove();
        if (started == null)
            return;
        long spent = System.currentTimeMillis() - started.longValue();
        String name = module != null && !module.isEmpty() ? module : "?"; //$NON-NLS-1$
        saveActive = false;
        saveUiUntilMs = System.currentTimeMillis() + 5_000L;
        SaveDebug.step("save-end", name + " " + outcome + " " + spent + "ms" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " skipWait=" + SAVE_SKIP.get() //$NON-NLS-1$
            + " waitModel=" + SAVE_WAIT.get() //$NON-NLS-1$
            + " lockerPatch=" + lockerPatch()); //$NON-NLS-1$
    }

    private static IEditorPart resolveEditor(ExecutionEvent event)
    {
        if (event != null)
        {
            try
            {
                IEditorPart fromEvent = HandlerUtil.getActiveEditor(event);
                if (fromEvent != null)
                    return fromEvent;
            }
            catch (RuntimeException ignored)
            {
            }
        }
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null)
            return null;
        return window.getActivePage().getActiveEditor();
    }

    private static String moduleLabel(BslXtextEditor editor)
    {
        IEditorInput input = editor.getEditorInput();
        if (input instanceof IFileEditorInput fileInput)
        {
            IFile file = fileInput.getFile();
            if (file != null)
                return file.getFullPath().toString();
        }
        return input != null ? input.getName() : "?"; //$NON-NLS-1$
    }

    /**
     * После сохранения поставщик документа сбрасывает у редактора признак «состояние
     * проверено», и первая клавиша зовёт {@code validateEditorInputState}: проверке нужна
     * блокировка файла, а её держит сборка, запущенная этим же сохранением. Ввод стоит всю
     * сборку, поверх — окно «Операция пользователя находится в состоянии ожидания».
     * Проверяем состояние сразу после сохранения, пока сборка блокировку ещё не взяла.
     * Взяла — ничего не делаем: первая клавиша подождёт, как штатно.
     * <p>
     * Сброс признака может прийти и позже команды (и при автосохранении), поэтому на поставщик
     * документа сохраняемого редактора вешается слушатель — один раз на поставщик.
     */
    private static final class StateValidationAfterSave
        implements IElementStateListener, IElementStateListenerExtension
    {
        private static final String TOPIC = "save-state-validation"; //$NON-NLS-1$
        private static final java.util.Map<IDocumentProvider, Boolean> LISTENED =
            new java.util.WeakHashMap<>();
        private static java.lang.ref.WeakReference<BslXtextEditor> saving =
            new java.lang.ref.WeakReference<>(null);

        private final java.lang.ref.WeakReference<BslXtextEditor> editor;

        private StateValidationAfterSave(BslXtextEditor editor)
        {
            this.editor = new java.lang.ref.WeakReference<>(editor);
        }

        /** UI-поток, {@code preExecute} команды сохранения. */
        static void beforeSave(BslXtextEditor bsl)
        {
            saving = new java.lang.ref.WeakReference<>(bsl);
            IDocumentProvider provider = bsl == null ? null : bsl.getDocumentProvider();
            if (provider == null || LISTENED.containsKey(provider))
                return;
            LISTENED.put(provider, Boolean.TRUE);
            provider.addElementStateListener(new StateValidationAfterSave(bsl));
        }

        /** UI-поток, сразу после успешной команды сохранения. */
        static void afterSave()
        {
            validateIfFree(saving.get(), "afterSave"); //$NON-NLS-1$
        }

        @Override
        public void elementStateValidationChanged(Object element, boolean isStateValidated)
        {
            if (isStateValidated)
                return;
            BslXtextEditor bsl = editor.get();
            if (bsl == null || element == null || !element.equals(bsl.getEditorInput()))
                return;
            // Сброс приходит изнутри операции сохранения или оповещения об изменении ресурсов:
            // там дерево ресурсов занято, проверку — следующим шагом UI.
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> validateIfFree(bsl, "invalidated")); //$NON-NLS-1$
        }

        private static void validateIfFree(BslXtextEditor bsl, String why)
        {
            if (bsl == null || bsl.getSite() == null || bsl.getEditorInput() == null)
                return;
            IEditorInput input = bsl.getEditorInput();
            if (!(bsl.getDocumentProvider() instanceof IDocumentProviderExtension extension)
                || extension.isStateValidated(input))
                return;
            IFile file = input.getAdapter(IFile.class);
            if (file == null)
                return;
            String busy = conflictingRunningJob(file);
            if (busy != null)
            {
                // #region agent log
                Global.tempLog(TOPIC, why + " skip busy=" + busy + " file=" + file.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // #endregion
                return;
            }
            long started = System.nanoTime();
            boolean ok = bsl.validateEditorInputState();
            // #region agent log
            Global.tempLog(TOPIC, why + " validated ok=" + ok + " ms=" //$NON-NLS-1$ //$NON-NLS-2$
                + (System.nanoTime() - started) / 1_000_000 + " file=" + file.getName()); //$NON-NLS-1$
            // #endregion
        }

        /** Имя выполняющегося задания, чьё правило пересекается с файлом; {@code null} — свободно. */
        private static String conflictingRunningJob(IFile file)
        {
            for (Job job : Job.getJobManager().find(null))
            {
                if (job.getState() != Job.RUNNING)
                    continue;
                org.eclipse.core.runtime.jobs.ISchedulingRule rule = job.getRule();
                if (rule != null && rule.isConflicting(file)
                    || job.belongsTo(ResourcesPlugin.FAMILY_AUTO_BUILD)
                    || job.belongsTo(ResourcesPlugin.FAMILY_MANUAL_BUILD))
                    return job.getName();
            }
            return null;
        }

        @Override public void elementDirtyStateChanged(Object element, boolean isDirty) {}
        @Override public void elementContentAboutToBeReplaced(Object element) {}
        @Override public void elementContentReplaced(Object element) {}
        @Override public void elementDeleted(Object element) {}
        @Override public void elementMoved(Object originalElement, Object movedElement) {}
        @Override public void elementStateChanging(Object element) {}
        @Override public void elementStateChangeFailed(Object element) {}
    }

    private static final class SaveDebug
    {
        private static final String DEBUG_TAG = "BslModuleSave"; //$NON-NLS-1$

        private SaveDebug() {}

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void step(String phase, String detail)
        {
            if (!isEnabled())
                return;
            if (detail == null || detail.isEmpty())
                Global.log(DEBUG_TAG, phase);
            else
                Global.log(DEBUG_TAG, phase + " " + detail); //$NON-NLS-1$
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(DEBUG_TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
