package tormozit;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * ВРЕМЕННАЯ ДИАГНОСТИКА: долгая запись модуля блокирует весь UI EDT.
 * <p>
 * Лог: {@code .tmp/temp-logs/bsl-module-save.log}. Снять после разбора.
 * Воспроизведение: сохранить изменённый модуль (Ctrl+S) и прислать лог.
 */
public final class BslModuleSaveDiagHook implements IStartup
{
    private static final String TOPIC = "bsl-module-save"; //$NON-NLS-1$

    private static final String CMD_SAVE = "org.eclipse.ui.file.save"; //$NON-NLS-1$
    private static final String CMD_SAVE_ALL = "org.eclipse.ui.file.saveAll"; //$NON-NLS-1$

    /** Пульс UI: {@code timerExec} раз в столько мс. */
    private static final int HEARTBEAT_MS = 200;
    /** Опрос сторожа. */
    private static final int WATCH_MS = 200;
    /** Считать UI зависшим, если пульс старше этого. 500 мс ловит обычный CA/GC. */
    private static final long STALL_LAG_MS = 1_500L;
    /** Первая выборка стека после начала записи. */
    private static final long FIRST_SAVE_DUMP_MS = 300L;
    /** Минимальный интервал повторной выборки стека в окне записи. */
    private static final long SAVE_DUMP_EVERY_MS = 400L;
    /** Сколько держать окно «идёт запись» после неявного сохранения (.bsl / dirty→clean). */
    private static final long SAVE_WINDOW_MS = 8_000L;

    private static final AtomicInteger SAVE_DEPTH = new AtomicInteger();
    private static final AtomicLong SAVE_STARTED_MS = new AtomicLong();
    private static final AtomicLong SAVE_WINDOW_UNTIL_MS = new AtomicLong();
    private static final AtomicLong HEARTBEAT_MS_CLOCK = new AtomicLong();
    private static final AtomicLong LAST_DUMP_MS = new AtomicLong();
    private static final AtomicInteger DUMP_IN_WINDOW = new AtomicInteger();
    private static final AtomicReference<String> LAST_SIG = new AtomicReference<>(""); //$NON-NLS-1$
    private static final AtomicReference<String> LAST_ACTIVITY = new AtomicReference<>("-"); //$NON-NLS-1$

    private static final Set<IEditorPart> HOOKED_EDITORS =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static volatile boolean installed;

    @Override
    public void earlyStartup()
    {
        try
        {
            ResourcesPlugin.getWorkspace().addResourceChangeListener(
                BslModuleSaveDiagHook::onResource,
                IResourceChangeEvent.PRE_BUILD
                    | IResourceChangeEvent.POST_BUILD
                    | IResourceChangeEvent.POST_CHANGE);
            log("earlyStartup: resource listener"); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError e)
        {
            log("earlyStartup resource/jobs: " + e); //$NON-NLS-1$
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(BslModuleSaveDiagHook::installUi);
    }

    private static void installUi()
    {
        if (installed)
            return;
        installed = true;
        try
        {
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null)
                commands.addExecutionListener(SAVE_COMMANDS);
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            startWatch(Display.getDefault());
            log("installUi: commands+editors+watch editor=" + describeEditor()); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError e)
        {
            log("installUi: " + e); //$NON-NLS-1$
        }
    }

    // -------------------------------------------------------------------------
    // Команда Save
    // -------------------------------------------------------------------------

    private static final IExecutionListener SAVE_COMMANDS = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!isSaveCommand(commandId))
                return;
            int depth = SAVE_DEPTH.incrementAndGet();
            long now = System.currentTimeMillis();
            if (depth == 1)
            {
                SAVE_STARTED_MS.set(now);
                DUMP_IN_WINDOW.set(0);
                LAST_SIG.set(""); //$NON-NLS-1$
            }
            markSaveWindow(now, "cmd " + commandId); //$NON-NLS-1$
            log("SAVE START depth=" + depth //$NON-NLS-1$
                + " cmd=" + commandId //$NON-NLS-1$
                + " ui=" + isUiThread() //$NON-NLS-1$
                + " thread=" + Thread.currentThread().getName() //$NON-NLS-1$
                + " " + describeEditor()); //$NON-NLS-1$
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            endSaveCommand(commandId, "ok"); //$NON-NLS-1$
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            endSaveCommand(commandId, "fail " + exception); //$NON-NLS-1$
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            endSaveCommand(commandId, "notHandled"); //$NON-NLS-1$
        }
    };

    private static void endSaveCommand(String commandId, String outcome)
    {
        if (!isSaveCommand(commandId))
            return;
        int depth = SAVE_DEPTH.decrementAndGet();
        if (depth < 0)
        {
            SAVE_DEPTH.set(0);
            depth = 0;
        }
        long now = System.currentTimeMillis();
        long started = SAVE_STARTED_MS.get();
        long spent = started > 0 ? now - started : -1L;
        markSaveWindow(now, "cmd-end " + commandId); //$NON-NLS-1$
        log("SAVE END depth=" + depth //$NON-NLS-1$
            + " cmd=" + commandId //$NON-NLS-1$
            + " outcome=" + outcome //$NON-NLS-1$
            + " spent=" + spent + "ms" //$NON-NLS-1$ //$NON-NLS-2$
            + " ui=" + isUiThread() //$NON-NLS-1$
            + " " + describeEditor()); //$NON-NLS-1$
        if (depth == 0 && spent >= FIRST_SAVE_DUMP_MS)
            dumpUi("save-end", spent); //$NON-NLS-1$
    }

    private static boolean isSaveCommand(String commandId)
    {
        return CMD_SAVE.equals(commandId) || CMD_SAVE_ALL.equals(commandId);
    }

    // -------------------------------------------------------------------------
    // Редактор: dirty → clean без команды Save
    // -------------------------------------------------------------------------

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            IEditorPart active = page.getActiveEditor();
            if (active != null)
                hookEditor(active);
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor != null)
                    hookEditor(editor);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void hookFromRef(IWorkbenchPartReference ref)
    {
        if (ref instanceof IEditorReference editorRef)
        {
            IEditorPart editor = editorRef.getEditor(false);
            if (editor != null)
                hookEditor(editor);
        }
    }

    private static void hookEditor(IEditorPart editor)
    {
        if (editor == null)
            return;
        synchronized (HOOKED_EDITORS)
        {
            if (!HOOKED_EDITORS.add(editor))
                return;
        }
        IPropertyListener listener = (source, propId) ->
        {
            if (propId != IEditorPart.PROP_DIRTY)
                return;
            boolean dirty = editor.isDirty();
            String name = editor.getEditorInput() != null
                ? editor.getEditorInput().getName()
                : editor.getClass().getSimpleName();
            if (dirty || SAVE_DEPTH.get() > 0)
                return;
            BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
            long now = System.currentTimeMillis();
            markSaveWindow(now, "dirty→clean " + name); //$NON-NLS-1$
            log("DIRTY off name=" + name //$NON-NLS-1$
                + " bsl=" + (bsl != null) //$NON-NLS-1$
                + " ui=" + isUiThread() //$NON-NLS-1$
                + " editor=" + editor.getClass().getSimpleName()); //$NON-NLS-1$
        };
        editor.addPropertyListener(listener);
        IEditorPart bsl = GetRef.getActiveBslEditor(editor);
        if (bsl != null && bsl != editor)
        {
            synchronized (HOOKED_EDITORS)
            {
                if (HOOKED_EDITORS.add(bsl))
                    bsl.addPropertyListener(listener);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Workspace
    // -------------------------------------------------------------------------

    private static void onResource(IResourceChangeEvent event)
    {
        if (event == null)
            return;
        long t0 = System.currentTimeMillis();
        int type = event.getType();
        List<String> bsl = new ArrayList<>(4);
        IResourceDelta delta = event.getDelta();
        if (delta != null)
        {
            try
            {
                delta.accept(child ->
                {
                    if (!(child.getResource() instanceof IFile file))
                        return true;
                    if (!"bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                        return false;
                    if ((child.getFlags() & IResourceDelta.CONTENT) == 0
                        && child.getKind() == IResourceDelta.CHANGED)
                        return false;
                    if (bsl.size() < 8)
                    {
                        bsl.add(kindName(child.getKind()) + " " //$NON-NLS-1$
                            + file.getFullPath());
                    }
                    else if (bsl.size() == 8)
                        bsl.add("…"); //$NON-NLS-1$
                    return false;
                });
            }
            catch (Exception e)
            {
                log("delta.accept: " + e); //$NON-NLS-1$
            }
        }
        long spent = System.currentTimeMillis() - t0;
        boolean saving = SAVE_DEPTH.get() > 0;
        if (!saving && spent < 200L)
            return;
        if (saving && bsl.isEmpty() && spent < 50L)
            return;
        log("RESOURCE type=" + eventTypeName(type) //$NON-NLS-1$
            + " spent=" + spent + "ms" //$NON-NLS-1$ //$NON-NLS-2$
            + " ui=" + isUiThread() //$NON-NLS-1$
            + " thread=" + Thread.currentThread().getName() //$NON-NLS-1$
            + " saveDepth=" + SAVE_DEPTH.get() //$NON-NLS-1$
            + " bsl=" + bsl.size() //$NON-NLS-1$
            + " " + bsl); //$NON-NLS-1$
    }

    // -------------------------------------------------------------------------
    // Сторож UI
    // -------------------------------------------------------------------------

    private static void startWatch(Display display)
    {
        Thread ui = display.getThread();
        if (ui == null)
            return;
        HEARTBEAT_MS_CLOCK.set(System.currentTimeMillis());
        Runnable[] beat = new Runnable[1];
        beat[0] = () ->
        {
            HEARTBEAT_MS_CLOCK.set(System.currentTimeMillis());
            if (!display.isDisposed())
                display.timerExec(HEARTBEAT_MS, beat[0]);
        };
        display.timerExec(HEARTBEAT_MS, beat[0]);
        Thread watch = new Thread(() ->
        {
            while (!display.isDisposed())
            {
                try
                {
                    Thread.sleep(WATCH_MS);
                }
                catch (InterruptedException e)
                {
                    return;
                }
                long now = System.currentTimeMillis();
                long lag = now - HEARTBEAT_MS_CLOCK.get();
                boolean cmdSave = SAVE_DEPTH.get() > 0;
                long saveElapsed = saveElapsed(now);
                boolean due = now - LAST_DUMP_MS.get() >= SAVE_DUMP_EVERY_MS;
                if (cmdSave && saveElapsed >= FIRST_SAVE_DUMP_MS && due)
                    dumpUi("save-sample lag=" + lag, saveElapsed); //$NON-NLS-1$
                else if (lag >= STALL_LAG_MS && due)
                    dumpUi("stall lag=" + lag, lag); //$NON-NLS-1$
            }
        }, "comfort-bsl-save-watch"); //$NON-NLS-1$
        watch.setDaemon(true);
        watch.start();
    }

    private static void dumpUi(String reason, long elapsedMs)
    {
        long now = System.currentTimeMillis();
        LAST_DUMP_MS.set(now);
        int n = DUMP_IN_WINDOW.incrementAndGet();
        Display display = Display.getDefault();
        Thread ui = display != null ? display.getThread() : null;
        StringBuilder sb = new StringBuilder();
        sb.append("DUMP #").append(n) //$NON-NLS-1$
            .append(" reason=").append(reason) //$NON-NLS-1$
            .append(" elapsed=").append(elapsedMs).append("ms") //$NON-NLS-1$ //$NON-NLS-2$
            .append(" saveDepth=").append(SAVE_DEPTH.get()) //$NON-NLS-1$
            .append(" activity=").append(LAST_ACTIVITY.get()) //$NON-NLS-1$
            .append(" watchThread=").append(Thread.currentThread().getName()); //$NON-NLS-1$
        if (ui == null)
        {
            sb.append(" ui=null"); //$NON-NLS-1$
            log(sb.toString());
            return;
        }
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo info = mx.getThreadInfo(ui.getId(), 80);
        String sig;
        if (info != null)
        {
            sb.append(" state=").append(info.getThreadState()); //$NON-NLS-1$
            if (info.getLockName() != null)
                sb.append(" lock=").append(info.getLockName()); //$NON-NLS-1$
            if (info.getLockOwnerName() != null)
                sb.append(" lockOwner=").append(info.getLockOwnerName()); //$NON-NLS-1$
            sig = signature(info.getStackTrace());
        }
        else
        {
            sig = signature(ui.getStackTrace());
        }
        String prev = LAST_SIG.getAndSet(sig);
        boolean same = !sig.isEmpty() && sig.equals(prev);
        sb.append(" sameStack=").append(same); //$NON-NLS-1$
        sb.append("\n  jobs ").append(jobsSnapshot()); //$NON-NLS-1$
        if (!same)
        {
            StackTraceElement[] stack = info != null ? info.getStackTrace() : ui.getStackTrace();
            int limit = Math.min(stack.length, 60);
            for (int i = 0; i < limit; i++)
                sb.append("\n  at ").append(stack[i]); //$NON-NLS-1$
        }
        log(sb.toString());
        Global.flushTempLogs();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void markSaveWindow(long now, String activity)
    {
        LAST_ACTIVITY.set(activity);
        boolean fresh = now > SAVE_WINDOW_UNTIL_MS.get();
        SAVE_WINDOW_UNTIL_MS.set(now + SAVE_WINDOW_MS);
        if (SAVE_DEPTH.get() <= 0 && fresh)
        {
            SAVE_STARTED_MS.set(now);
            DUMP_IN_WINDOW.set(0);
            LAST_SIG.set(""); //$NON-NLS-1$
        }
    }

    private static long saveElapsed(long now)
    {
        long started = SAVE_STARTED_MS.get();
        return started > 0 ? now - started : 0L;
    }

    private static boolean isUiThread()
    {
        return Display.getCurrent() != null;
    }

    private static String describeEditor()
    {
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
                return "no-workbench"; //$NON-NLS-1$
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null)
                return "no-window"; //$NON-NLS-1$
            IEditorPart editor = window.getActivePage().getActiveEditor();
            if (editor == null)
                return "no-editor"; //$NON-NLS-1$
            String input = editor.getEditorInput() != null
                ? editor.getEditorInput().getName()
                : "?"; //$NON-NLS-1$
            BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
            String bslName = ""; //$NON-NLS-1$
            if (bsl != null && bsl.getEditorInput() != null)
                bslName = " bslInput=" + bsl.getEditorInput().getName(); //$NON-NLS-1$
            return "editor=" + editor.getClass().getSimpleName() //$NON-NLS-1$
                + " input=" + input //$NON-NLS-1$
                + " dirty=" + editor.isDirty() //$NON-NLS-1$
                + " bsl=" + (bsl != null) //$NON-NLS-1$
                + bslName;
        }
        catch (RuntimeException | LinkageError e)
        {
            return "editor-err " + e; //$NON-NLS-1$
        }
    }

    private static String eventTypeName(int type)
    {
        return switch (type)
        {
            case IResourceChangeEvent.PRE_BUILD -> "PRE_BUILD"; //$NON-NLS-1$
            case IResourceChangeEvent.POST_BUILD -> "POST_BUILD"; //$NON-NLS-1$
            case IResourceChangeEvent.POST_CHANGE -> "POST_CHANGE"; //$NON-NLS-1$
            case IResourceChangeEvent.PRE_CLOSE -> "PRE_CLOSE"; //$NON-NLS-1$
            case IResourceChangeEvent.PRE_DELETE -> "PRE_DELETE"; //$NON-NLS-1$
            default -> Integer.toString(type);
        };
    }

    private static String kindName(int kind)
    {
        return switch (kind)
        {
            case IResourceDelta.ADDED -> "ADD"; //$NON-NLS-1$
            case IResourceDelta.REMOVED -> "REM"; //$NON-NLS-1$
            case IResourceDelta.CHANGED -> "CHG"; //$NON-NLS-1$
            default -> Integer.toString(kind);
        };
    }

    private static String signature(StackTraceElement[] stack)
    {
        if (stack == null || stack.length == 0)
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        int n = Math.min(stack.length, 10);
        for (int i = 0; i < n; i++)
        {
            sb.append(stack[i].getClassName()).append('.')
                .append(stack[i].getMethodName()).append(':')
                .append(stack[i].getLineNumber()).append('|');
        }
        return sb.toString();
    }

    private static String jobsSnapshot()
    {
        Job[] jobs = Job.getJobManager().find(null);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Job job : jobs)
        {
            int state = job.getState();
            if (state != Job.RUNNING && state != Job.WAITING)
                continue;
            n++;
            if (n > 20)
            {
                sb.append(" …"); //$NON-NLS-1$
                break;
            }
            String st = state == Job.RUNNING ? "RUN" //$NON-NLS-1$
                : state == Job.WAITING ? "WAIT" : "SLEEP"; //$NON-NLS-1$ //$NON-NLS-2$
            Thread thread = job.getThread();
            sb.append('[').append(st).append("] ").append(job.getName()); //$NON-NLS-1$
            if (thread != null)
                sb.append('{').append(thread.getName()).append('}');
            sb.append("; "); //$NON-NLS-1$
        }
        return n == 0 ? "none" : n + " " + sb; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void log(String text)
    {
        Global.tempLog(TOPIC, text);
    }
}
