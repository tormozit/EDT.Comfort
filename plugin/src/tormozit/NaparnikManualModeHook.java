package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.AbstractDocument;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * В режиме «Ручной» 1С:Напарник не меняет модуль без явной команды
 * («Предложи код», Tab / Ctrl+Enter). Штатный ViewModel после вставки вызывает
 * {@code askNew}; запись в документ — {@code CodeCompletionContext.replace};
 * серая подсказка — {@code HintPainter.setHintAt}.
 *
 * @see <a href="https://github.com/tormozit/EDT.Comfort/issues/378">issue 378</a>
 */
public final class NaparnikManualModeHook implements IStartup
{
    /** Временная диагностика issue 378: подсказка Напарника после вставки в режиме «Ручной». */
    private static final boolean LOG_ENABLED = true;
    private static final String LOG = "naparnik-378"; //$NON-NLS-1$

    /**
     * Временный выключатель влияния на Напарника (issue 378). При {@code false} хук ничего не
     * запрещает и ничего не откатывает — остаётся только логирование, в том числе строки о том,
     * что патч сделал бы. Вернуть {@code true} после проверки поведения без патча.
     */
    private static final boolean PATCH_ENABLED = true;

    /** Функции для вставок в классы Напарника — их делает ранний бандл ({@code tormozit.early.Activator.NaparnikWeaving}). */
    static final String PROP_REPLACE = "tormozit.naparnik.replace"; //$NON-NLS-1$
    static final String PROP_HINT = "tormozit.naparnik.hint"; //$NON-NLS-1$
    /** Итог вплетения из раннего бандла. */
    private static final String PROP_PATCH = "tormozit.naparnik.patch"; //$NON-NLS-1$

    private static final String PREF_NODE = "com.e1c.edt.ai.ui"; //$NON-NLS-1$
    private static final String PREF_KEY = "stringPreferenceCodeCompletionPolicy"; //$NON-NLS-1$

    private static final String VM = "com.e1c.edt.ai.ui.CodeCompletionViewModel"; //$NON-NLS-1$

    private static final String CMD_PASTE = "org.eclipse.ui.edit.paste"; //$NON-NLS-1$
    private static final String CMD_SUGGEST = "com.e1c.edt.ai.ui.commands.suggest.ai"; //$NON-NLS-1$
    private static final String CMD_ACCEPT = "com.e1c.edt.ai.ui.commands.accept.ai"; //$NON-NLS-1$
    private static final String CMD_ACCEPT_PART = "com.e1c.edt.ai.ui.commands.acceptpart.ai"; //$NON-NLS-1$
    private static final String CMD_ACCEPT_LINE = "com.e1c.edt.ai.ui.commands.acceptline.ai"; //$NON-NLS-1$

    private static final String NAPARNIK_PACKAGE = "com.e1c.edt.ai"; //$NON-NLS-1$
    /** Инструменты агента чата ({@code EditMcpTool} и др.). */
    private static final String NAPARNIK_TOOLS_PACKAGE = "com.e1c.edt.ai.tools."; //$NON-NLS-1$
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private static final int MAX_ATTACH_ATTEMPTS = 100;
    private static final int[] SUPPRESS_DELAYS_MS = { 0, 50, 150, 400, 1000, 2500 };
    private static volatile boolean paintLogInstalled;

    /** Узлы настроек, где может лежать политика; порядок — от самого вероятного. */
    private static final String[] POLICY_NODES =
        { PREF_NODE, "com.e1c.edt.ai", "com.e1c.edt.ai.ui.common" }; //$NON-NLS-1$ //$NON-NLS-2$

    /** Момент последней вставки в редактор — чтобы отличить показ подсказки при вставке. */
    private static volatile long lastPasteNanos;

    private static volatile String cachedPolicy;
    private static volatile boolean policyListenerInstalled;

    private static final AtomicBoolean booted = new AtomicBoolean();
    /**
     * Принятие подсказки Напарника (Tab, Ctrl+Enter, команды «принять») разрешено на
     * {@link #APPLY_WINDOW_MS} от последнего такого события. Окно, а не счётчик: штатный Tab
     * запускает и клавишу, и команды Напарника, у которых не всегда есть парное завершение, —
     * счётчик зависал на 1 и выключал защиту до конца сеанса (лог 21.10.2026 15:27).
     */
    private static final long APPLY_WINDOW_MS = 800;
    private static volatile long applyGrantedNanos;
    private static volatile boolean applyGranted;
    private static final AtomicInteger allowSuggest = new AtomicInteger();
    private static final AtomicInteger pasteActive = new AtomicInteger();
    private static final AtomicBoolean reverting = new AtomicBoolean();
    private static volatile IDocument pendingSuppressDoc;
    private static volatile boolean suppressSeriesArmed;
    private static final Map<IDocument, DocumentGuard> guards = new WeakHashMap<>();
    private static final IdentityHashMap<DtGranularEditor<?>, Boolean> granularHooked =
        new IdentityHashMap<>();

    /** Без UI: сразу после {@code Activator.start}, до workbench. */
    public static void bootFromActivator()
    {
        if (!booted.compareAndSet(false, true))
            return;
        ServiceRecovery.start();
        System.getProperties().put(PROP_REPLACE,
            (Predicate<Object>) NaparnikManualModeHook::allowReplace);
        System.getProperties().put(PROP_HINT, (Predicate<Object>) NaparnikManualModeHook::allowHint);
        if (LOG_ENABLED)
        {
            Bundle common = Platform.getBundle("com.e1c.edt.ai.ui.common"); //$NON-NLS-1$
            Global.tempLog(LOG, "bootFromActivator patch=" + PATCH_ENABLED + " напарник=" //$NON-NLS-1$ //$NON-NLS-2$
                + (common != null ? common.getVersion() + " state=" + common.getState() : "нет") //$NON-NLS-1$ //$NON-NLS-2$
                + " вплетение=" + System.getProperty(PROP_PATCH)); //$NON-NLS-1$
        }
    }

    static void stopServiceRecovery()
    {
        NaparnikAssistRow.stopRequestPolicy();
        ServiceRecovery.stop();
    }

    @Override
    public void earlyStartup()
    {
        bootFromActivator();
        Display display = Display.getDefault();
        if (display == null)
            return;
        display.asyncExec(NaparnikManualModeHook::installUi);
    }

    private static void installUi()
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            Display d = Display.getDefault();
            if (d != null && !d.isDisposed())
                d.timerExec(200, NaparnikManualModeHook::installUi);
            return;
        }
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commands != null)
            commands.addExecutionListener(COMMAND_LISTENER);

        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        ServiceRecovery.installStatusMenu(display);
        if (display != null && !paintLogInstalled)
        {
            paintLogInstalled = true;
            Listener verify = NaparnikManualModeHook::onDisplayVerify;
            display.addFilter(SWT.Verify, verify);
            display.addFilter(ST.VerifyKey, verify);
            // Фильтр SWT.Paint снят: он срабатывает раньше слушателя виджета, то есть его
            // работа идёт, пока канва уже закрашена фоном, а буфер ещё не выложен. Для линейки
            // номеров это и есть видимая вспышка. Диагностику отрисовки в фильтр не возвращать.
        }

        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);
        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
            }
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            IEditorPart active = page.getActiveEditor();
            if (active != null)
                hookEditor(active);
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null && ed != active)
                    hookEditor(ed);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override
            public void partInputChanged(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference r)
            {
            }

            @Override
            public void partClosed(IWorkbenchPartReference r)
            {
            }

            @Override
            public void partDeactivated(IWorkbenchPartReference r)
            {
            }

            @Override
            public void partHidden(IWorkbenchPartReference r)
            {
            }

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (ref instanceof IEditorReference editorRef)
                    hookEditor(editorRef.getEditor(false));
            }
        });
    }

    private static void hookEditor(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
            Display.getDefault().asyncExec(() -> attachBsl(bsl, 0));
        else if (editor instanceof DtGranularEditor<?> granular)
            hookGranular(granular);
        else
        {
            ITextEditor text = TextEditor.resolveTextEditor(editor);
            if (text != null)
                Display.getDefault().asyncExec(() -> attachTextEditor(text, 0));
        }
    }

    private static void hookGranular(DtGranularEditor<?> editor)
    {
        hookGranularPage(editor, 0);
        if (granularHooked.put(editor, Boolean.TRUE) != null)
            return;
        editor.addPageChangedListener(new IPageChangedListener()
        {
            @Override
            public void pageChanged(PageChangedEvent event)
            {
                Object selected = event.getSelectedPage();
                if (selected instanceof DtGranularEditorXtextEditorPage<?> page)
                {
                    IEditorPart embedded = page.getEmbeddedEditor();
                    if (embedded instanceof BslXtextEditor bsl)
                        Display.getDefault().asyncExec(() -> attachBsl(bsl, 0));
                    else
                        hookGranularPage(editor, 0);
                }
            }
        });
    }

    private static void hookGranularPage(DtGranularEditor<?> editor, int attempt)
    {
        IFormPage active = editor.getActivePageInstance();
        if (active instanceof DtGranularEditorXtextEditorPage<?> page)
        {
            IEditorPart embedded = page.getEmbeddedEditor();
            if (embedded instanceof BslXtextEditor bsl)
            {
                Display.getDefault().asyncExec(() -> attachBsl(bsl, 0));
                return;
            }
        }
        if (attempt >= MAX_ATTACH_ATTEMPTS || isWorkbenchClosing())
            return;
        Display.getDefault().asyncExec(() -> hookGranularPage(editor, attempt + 1));
    }

    private static void attachBsl(BslXtextEditor editor, int attempt)
    {
        if (editor.getSite() == null || isWorkbenchClosing())
            return;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof SourceViewer) || viewer.getDocument() == null)
        {
            if (attempt >= MAX_ATTACH_ATTEMPTS)
                return;
            Display.getDefault().asyncExec(() -> attachBsl(editor, attempt + 1));
            return;
        }
        attachDocument(viewer.getDocument());
    }

    private static void attachTextEditor(ITextEditor editor, int attempt)
    {
        if (editor.getSite() == null || isWorkbenchClosing())
            return;
        ISourceViewer viewer = TextEditor.getSourceViewer(editor);
        if (viewer == null || viewer.getDocument() == null)
        {
            if (attempt >= MAX_ATTACH_ATTEMPTS)
                return;
            Display.getDefault().asyncExec(() -> attachTextEditor(editor, attempt + 1));
            return;
        }
        attachDocument(viewer.getDocument());
    }

    private static void attachDocument(IDocument document)
    {
        if (document == null)
            return;
        synchronized (guards)
        {
            if (guards.containsKey(document))
                return;
            DocumentGuard guard = new DocumentGuard();
            document.addDocumentListener(guard);
            guards.put(document, guard);
        }
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    private static void grantApply()
    {
        applyGrantedNanos = System.nanoTime();
        applyGranted = true;
    }

    /** Остаток окна принятия, мс; {@code 0} — принятие не разрешено. */
    private static long applyLeftMs()
    {
        if (!applyGranted)
            return 0;
        long left = APPLY_WINDOW_MS * 1_000_000L - (System.nanoTime() - applyGrantedNanos);
        return left > 0 ? left / 1_000_000L + 1 : 0;
    }

    static boolean allowReplace(Object text)
    {
        boolean allow = !isRestrictedPolicy() || applyLeftMs() > 0;
        if (LOG_ENABLED)
        {
            Global.tempLog(LOG, "allowReplace allow=" + (!PATCH_ENABLED || allow) + " policy=" + readPolicy() //$NON-NLS-1$ //$NON-NLS-2$
                + " allowApply=" + applyLeftMs() + " послеВставки=" + sinceLastPaste()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return !PATCH_ENABLED || allow;
    }

    static boolean allowHint(Object text)
    {
        boolean allow = !isRestrictedPolicy() || allowSuggest.get() > 0 || applyLeftMs() > 0;
        boolean result = !PATCH_ENABLED || allow;
        // Скрытие подсказки (текст null) — сотни строк без пользы, пишем только показ.
        if (LOG_ENABLED && text != null)
        {
            Global.tempLog(LOG, "ПОКАЗ ПОДСКАЗКИ: пропущен=" + result + " политика=" + readPolicy() //$NON-NLS-1$ //$NON-NLS-2$
                + " послеВставки=" + sinceLastPaste() + " allowSuggest=" + allowSuggest.get() //$NON-NLS-1$ //$NON-NLS-2$
                + " allowApply=" + applyLeftMs() + " длина=" + (text instanceof String s ? s.length() : -1) //$NON-NLS-1$ //$NON-NLS-2$
                + " поток=" + Thread.currentThread().getName() + " стек=" + callerFrames()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    /** Кадры Напарника и Комфорта в стеке вызова — кто дошёл до показа подсказки. */
    private static String callerFrames()
    {
        return STACK_WALKER.walk(frames -> frames
            .filter(f -> f.getClassName().startsWith(NAPARNIK_PACKAGE)
                || f.getClassName().startsWith("tormozit.") //$NON-NLS-1$
                    && !f.getClassName().startsWith(NaparnikManualModeHook.class.getName()))
            .limit(12)
            .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() //$NON-NLS-1$
                + ":" + f.getLineNumber()) //$NON-NLS-1$
            .reduce((a, b) -> a + " < " + b) //$NON-NLS-1$
            .orElse("-")); //$NON-NLS-1$
    }

    /** Сколько прошло с последней вставки, для строки о показе подсказки. */
    private static String sinceLastPaste()
    {
        long paste = lastPasteNanos;
        if (paste == 0)
            return "вставок не было"; //$NON-NLS-1$
        return (System.nanoTime() - paste) / 1_000_000L + "мс"; //$NON-NLS-1$
    }

    /**
     * Display-фильтр ловит {@code SWT.Verify} любого текстового виджета. Сброс ViewModel
     * Напарника нужен только при вставке в модуль: иначе заполнение фильтра панели, журнала
     * или «Недавних мест» вызывает {@code reset()} у активного редактора и мигает линейка.
     */
    private static void onDisplayVerify(Event event)
    {
        if (event == null)
            return;
        boolean accept = isAcceptHotkey(event);
        boolean suggest = isSuggestHotkey(event);
        if (!accept && !suggest)
            return;
        boolean hooked = isHookedEditorStyledText(event.widget);
        if (LOG_ENABLED)
        {
            Global.tempLog(LOG, "клавиша accept=" + accept + " suggest=" + suggest + " редактор=" + hooked //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " type=" + event.type + " allowApply=" + applyLeftMs() + " allowSuggest=" + allowSuggest.get()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (!hooked)
            return;
        if (accept)
            grantApply();
        if (suggest)
            allowSuggest.incrementAndGet();
    }

    private static final IExecutionListener COMMAND_LISTENER = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (commandId == null)
                return;
            if (CMD_PASTE.equals(commandId))
            {
                Display display = Display.getCurrent();
                Control focus = display != null ? display.getFocusControl() : null;
                boolean ours = isHookedEditorStyledText(focus);
                if (LOG_ENABLED)
                {
                    Global.tempLog(LOG, "paste вплетение=" + System.getProperty(PROP_PATCH)); //$NON-NLS-1$
                    Global.tempLog(LOG, "paste ours=" + ours + " policy=" + readPolicy() //$NON-NLS-1$ //$NON-NLS-2$
                        + " allowApply=" + applyLeftMs() + " allowSuggest=" + allowSuggest.get() //$NON-NLS-1$ //$NON-NLS-2$
                        + " pasteActive=" + pasteActive.get() + " фокус=" //$NON-NLS-1$ //$NON-NLS-2$
                        + (focus != null ? focus.getClass().getName() : "null")); //$NON-NLS-1$
                }
                if (ours)
                {
                    lastPasteNanos = System.nanoTime();
                    pasteActive.incrementAndGet();
                    allowSuggest.set(0);
                    scheduleSuppress(null);
                }
            }
            if (CMD_SUGGEST.equals(commandId))
                allowSuggest.incrementAndGet();
            if (CMD_ACCEPT.equals(commandId) || CMD_ACCEPT_PART.equals(commandId)
                || CMD_ACCEPT_LINE.equals(commandId))
                grantApply();
            if (LOG_ENABLED && (commandId.startsWith(NAPARNIK_PACKAGE) || commandId.contains("paste"))) //$NON-NLS-1$
            {
                Global.tempLog(LOG, "команда " + commandId + " allowApply=" + applyLeftMs() //$NON-NLS-1$ //$NON-NLS-2$
                    + " allowSuggest=" + allowSuggest.get()); //$NON-NLS-1$
            }
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            clearCommandFlags(commandId);
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            clearCommandFlags(commandId);
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            clearCommandFlags(commandId);
        }
    };

    private static void clearCommandFlags(String commandId)
    {
        if (CMD_PASTE.equals(commandId))
            pasteActive.updateAndGet(v -> Math.max(0, v - 1));
        // Окно — от завершения команды: запись в документ идёт и после preExecute.
        if (CMD_ACCEPT.equals(commandId) || CMD_ACCEPT_PART.equals(commandId)
            || CMD_ACCEPT_LINE.equals(commandId))
            grantApply();
    }

    private static void scheduleSuppress(IDocument document)
    {
        if (LOG_ENABLED)
        {
            Global.tempLog(LOG, "scheduleSuppress document=" + System.identityHashCode(document) //$NON-NLS-1$
                + " policy=" + readPolicy() + " armed=" + suppressSeriesArmed); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!isRestrictedPolicy())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (document != null)
            pendingSuppressDoc = document;
        if (suppressSeriesArmed)
            return;
        suppressSeriesArmed = true;
        int[] left = { SUPPRESS_DELAYS_MS.length };
        for (int delay : SUPPRESS_DELAYS_MS)
        {
            display.timerExec(delay, () ->
            {
                try
                {
                    if (applyLeftMs() > 0 || allowSuggest.get() > 0)
                        return;
                    suppressViewModel(pendingSuppressDoc);
                }
                finally
                {
                    left[0]--;
                    if (left[0] <= 0)
                        suppressSeriesArmed = false;
                }
            });
        }
    }

    private static void suppressViewModel(IDocument document)
    {
        if (!isRestrictedPolicy())
            return;
        List<Object> found = new ArrayList<>();
        if (document != null)
            collectViewModels(listDocumentListeners(document), found);
        IEditorPart active = null;
        try
        {
            if (PlatformUI.isWorkbenchRunning() && PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                && PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage() != null)
                active = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        }
        catch (Exception ignored)
        {
        }
        StyledText widget = findStyledText(active);
        if (widget != null && !widget.isDisposed())
        {
            collectViewModels(widget.getListeners(ST.VerifyKey), found);
            collectViewModels(widget.getListeners(SWT.Modify), found);
        }
        for (Object vm : found)
        {
            if (!PATCH_ENABLED)
                continue;
            Object balanced = Global.invoke(vm, "isBalanced"); //$NON-NLS-1$
            if (LOG_ENABLED)
            {
                Global.tempLog(LOG, "suppressViewModel balanced=" + balanced); //$NON-NLS-1$
            }
            String lastJob = cancelJob(vm, "lastJob"); //$NON-NLS-1$
            String commitJob = cancelJob(vm, "commitJob"); //$NON-NLS-1$
            cancelJob(vm, "lastUpdateMethodJob"); //$NON-NLS-1$
            if (!"нет".equals(lastJob) || !"нет".equals(commitJob)) //$NON-NLS-1$ //$NON-NLS-2$
                if (LOG_ENABLED)
                {
                    Global.tempLog(LOG, "оборвано задание Напарника: lastJob=" + lastJob //$NON-NLS-1$
                        + " commitJob=" + commitJob + " послеВставки=" + sinceLastPaste()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            if (Boolean.TRUE.equals(balanced))
                continue;
            Global.invokeVoid(vm, "reset"); //$NON-NLS-1$
        }
    }

    /** Модель завершения кода Напарника, подключённая к виджету редактора; {@code null} — нет. */
    static Object findViewModel(StyledText widget)
    {
        if (widget == null || widget.isDisposed())
            return null;
        List<Object> found = new ArrayList<>();
        collectViewModels(widget.getListeners(ST.VerifyKey), found);
        if (found.isEmpty())
            collectViewModels(widget.getListeners(SWT.Modify), found);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Разрешает показ серой подсказки в режиме «Ручной» — так же, как команда «Предложи код».
     * Для подсказки, которую показывают по явному действию пользователя, а не по вводу.
     */
    static void allowHintByUserAction()
    {
        allowSuggest.incrementAndGet();
        if (LOG_ENABLED)
        {
            Global.tempLog(LOG, "allowHintByUserAction allowSuggest=" + allowSuggest.get() + " стек=" + callerFrames()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Политика завершения кода Напарника ({@code off}, {@code manual}, …); {@code ?} — не прочитана. */
    static String completionPolicy()
    {
        return readPolicy();
    }

    private static void collectViewModels(Object[] listeners, List<Object> found)
    {
        if (listeners == null)
            return;
        for (Object listener : listeners)
            collectViewModels(listener, found);
    }

    private static void collectViewModels(List<?> listeners, List<Object> found)
    {
        if (listeners == null)
            return;
        for (Object listener : listeners)
            collectViewModels(listener, found);
    }

    private static void collectViewModels(Object listener, List<Object> found)
    {
        if (listener == null)
            return;
        Object actual = listener;
        Object inner = Global.invoke(listener, "getEventListener"); //$NON-NLS-1$
        if (inner != null)
            actual = inner;
        if (VM.equals(actual.getClass().getName()) && !found.contains(actual))
            found.add(actual);
    }

    /**
     * Отменяет задание Напарника и возвращает описание того, что реально было отменено:
     * без этого по логу не отличить «Напарник не собирался показывать подсказку» от «мы
     * оборвали его раньше, чем он дошёл до setHintAt» (issue 378).
     *
     * @return состояние задания до отмены: {@code нет} — поля не было либо оно пустое,
     *         {@code спит} / {@code ждёт} / {@code работает} — задание было живым
     */
    private static String cancelJob(Object vm, String field)
    {
        Object job = Global.getField(vm, field);
        if (!(job instanceof Job j))
            return "нет"; //$NON-NLS-1$
        String state = switch (j.getState())
        {
            case Job.SLEEPING -> "спит"; //$NON-NLS-1$
            case Job.WAITING -> "ждёт"; //$NON-NLS-1$
            case Job.RUNNING -> "работает"; //$NON-NLS-1$
            default -> "простаивает"; //$NON-NLS-1$
        };
        j.cancel();
        return state;
    }

    private static boolean isHookedEditorStyledText(Object widget)
    {
        if (!(widget instanceof StyledText styled) || styled.isDisposed())
            return false;
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
                return false;
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                IWorkbenchPage page = window.getActivePage();
                if (page == null)
                    continue;
                IEditorPart active = page.getActiveEditor();
                if (styled == findStyledText(active))
                    return true;
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor != null && editor != active && styled == findStyledText(editor))
                        return true;
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    private static StyledText findStyledText(IEditorPart editor)
    {
        if (editor == null)
            return null;
        if (editor instanceof BslXtextEditor bsl)
        {
            ISourceViewer viewer = bsl.getInternalSourceViewer();
            return viewer != null ? viewer.getTextWidget() : null;
        }
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage page = granular.getActivePageInstance();
            if (page instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor)
                    return findStyledText(embedded);
            }
        }
        ITextEditor text = editor instanceof ITextEditor te ? te : TextEditor.resolveTextEditor(editor);
        if (text == null)
            return null;
        ISourceViewer viewer = TextEditor.getSourceViewer(text);
        return viewer != null ? viewer.getTextWidget() : null;
    }

    private static List<IDocumentListener> listDocumentListeners(IDocument doc)
    {
        List<IDocumentListener> result = new ArrayList<>();
        if (!(doc instanceof AbstractDocument))
            return result;
        try
        {
            Field field = AbstractDocument.class.getDeclaredField("fDocumentListeners"); //$NON-NLS-1$
            field.setAccessible(true);
            Object listObj = field.get(doc);
            if (listObj instanceof ListenerList<?> listenerList)
            {
                for (Object o : listenerList)
                {
                    if (o instanceof IDocumentListener l)
                        result.add(l);
                }
            }
            else if (listObj instanceof Iterable<?> iterable)
            {
                for (Object o : iterable)
                {
                    if (o instanceof IDocumentListener l)
                        result.add(l);
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return result;
    }

    private static final class DocumentGuard implements IDocumentListener
    {
        private String pendingOld = ""; //$NON-NLS-1$

        @Override
        public void documentAboutToBeChanged(DocumentEvent event)
        {
            if (event == null || event.getDocument() == null)
                return;
            try
            {
                pendingOld = event.getDocument().get(event.getOffset(), event.getLength());
            }
            catch (BadLocationException e)
            {
                pendingOld = ""; //$NON-NLS-1$
            }
        }

        @Override
        public void documentChanged(DocumentEvent event)
        {
            if (event == null || reverting.get())
                return;
            String inserted = event.getText() != null ? event.getText() : ""; //$NON-NLS-1$
            boolean naparnik = stackHasNaparnik();
            // Обычный ввод — по строке на символ; пишем только вставку и правки Напарника.
            if (LOG_ENABLED && (naparnik || pasteActive.get() > 0))
            {
                Global.tempLog(LOG, "documentChanged document=" + System.identityHashCode(event.getDocument()) //$NON-NLS-1$
                    + " offset=" + event.getOffset() + " removed=" + event.getLength() + " inserted=" + inserted.length() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " naparnik=" + naparnik + " pasteActive=" + pasteActive.get() //$NON-NLS-1$ //$NON-NLS-2$
                    + " allowApply=" + applyLeftMs() + " policy=" + readPolicy()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (pasteActive.get() > 0 || (naparnik && inserted.length() > 1))
                scheduleSuppress(event.getDocument());
            // Правка модуля агентом Напарника (EditMcpTool) — явное действие чата, не встроенное
            // автодополнение: откатывать её нельзя (issue 956).
            boolean revert = PATCH_ENABLED
                && isRestrictedPolicy() && naparnik && applyLeftMs() == 0 && pasteActive.get() == 0
                && !stackHasNaparnikTool();
            if (!revert)
                return;
            IDocument document = event.getDocument();
            String oldText = pendingOld;
            int offset = event.getOffset();
            reverting.set(true);
            Display display = Display.getDefault();
            if (display == null)
            {
                reverting.set(false);
                return;
            }
            display.asyncExec(() ->
            {
                try
                {
                    document.replace(offset, inserted.length(), oldText);
                }
                catch (Exception ignored)
                {
                }
                finally
                {
                    reverting.set(false);
                }
            });
        }
    }

    private static boolean isAcceptHotkey(Event event)
    {
        int mods = event.stateMask & SWT.MODIFIER_MASK;
        boolean tab = event.keyCode == SWT.TAB || event.character == '\t';
        if (tab && mods == 0)
            return true;
        boolean enter = event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR
            || event.character == '\r';
        if (enter && mods == SWT.CTRL)
            return true;
        return enter && mods == (SWT.CTRL | SWT.ALT);
    }

    private static boolean isSuggestHotkey(Event event)
    {
        int mods = event.stateMask & SWT.MODIFIER_MASK;
        return (event.keyCode == SWT.SPACE || event.character == ' ')
            && mods == (SWT.CTRL | SWT.ALT);
    }

    private static boolean isRestrictedPolicy()
    {
        String policy = readPolicy();
        return "manual".equalsIgnoreCase(policy) || "off".equalsIgnoreCase(policy); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Политика Напарника с кэшем: чтение стоит рефлексии ({@code BaseActivator.getDefault()} →
     * {@code getPreferenceStore().getString(...)}), а спрашивают её на каждое решение хука.
     *
     * <p>Кэш заполняется только если удалось подписаться на изменение настройки: без подписки
     * устаревшее значение пережило бы переключение режима, поэтому там читаем как раньше.
     */
    private static String readPolicy()
    {
        String cached = cachedPolicy;
        if (cached != null)
            return cached;
        String value = readPolicyUncached();
        if (installPolicyListener())
            cachedPolicy = value;
        return value;
    }

    /**
     * Подписка на изменение настройки политики — один раз за сеанс.
     *
     * @return {@code true}, если подписка стоит и кэшу можно доверять
     */
    private static synchronized boolean installPolicyListener()
    {
        if (policyListenerInstalled)
            return true;
        IPreferenceChangeListener listener = event ->
        {
            if (PREF_KEY.equals(event.getKey()))
                cachedPolicy = null;
        };
        boolean any = false;
        for (String node : POLICY_NODES)
        {
            try
            {
                IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(node);
                if (preferences == null)
                    continue;
                preferences.addPreferenceChangeListener(listener);
                any = true;
            }
            catch (Exception ignored)
            {
            }
        }
        policyListenerInstalled = any;
        return any;
    }

    private static String readPolicyUncached()
    {
        String fromStore = readPolicyFromNaparnikStore();
        if (fromStore != null)
            return fromStore;
        for (String node : POLICY_NODES)
        {
            try
            {
                String value = InstanceScope.INSTANCE.getNode(node).get(PREF_KEY, null);
                if (value != null && !value.isBlank())
                    return value;
            }
            catch (Exception ignored)
            {
            }
        }
        return "?"; //$NON-NLS-1$
    }

    private static String readPolicyFromNaparnikStore()
    {
        try
        {
            Bundle bundle = Platform.getBundle(PREF_NODE);
            if (bundle == null)
                return null;
            Class<?> activator = bundle.loadClass("com.e1c.edt.ai.ui.BaseActivator"); //$NON-NLS-1$
            Object instance = Global.invoke(activator, "getDefault"); //$NON-NLS-1$
            if (instance == null)
                return null;
            Object store = Global.invoke(instance, "getPreferenceStore"); //$NON-NLS-1$
            Object value = Global.invoke(store, "getString", PREF_KEY); //$NON-NLS-1$
            if (value instanceof String s && !s.isBlank())
                return s;
        }
        catch (Throwable ignored)
        {
        }
        return null;
    }

    /**
     * Есть ли в текущем стеке кадр Напарника.
     *
     * <p>Вызывается на каждое изменение документа, поэтому {@code Thread.getStackTrace()} здесь
     * не годится: он материализует весь стек целиком, а в Xtext-редакторе стеки глубокие.
     * {@link StackWalker} обходит кадры лениво и останавливается на первом подходящем.
     */
    private static boolean stackHasNaparnikTool()
    {
        return STACK_WALKER.walk(
            frames -> frames.anyMatch(frame -> frame.getClassName().startsWith(NAPARNIK_TOOLS_PACKAGE)));
    }

    private static boolean stackHasNaparnik()
    {
        return STACK_WALKER.walk(
            frames -> frames.anyMatch(frame -> frame.getClassName().startsWith(NAPARNIK_PACKAGE)));
    }

    /** Временный отказ занятого ключа не должен оставлять службу выключенной до перезапуска. */
    private static final class ServiceRecovery
    {
        private static final String NOTIFY = "tormozit.naparnik.tokenNotify"; //$NON-NLS-1$
        private static final String PENDING = "tormozit.naparnik.tokenPending"; //$NON-NLS-1$
        private static final String TOKEN_MESSAGE = "tormozit.naparnik.tokenMessage"; //$NON-NLS-1$
        private static final String CHAT_PAGE = "tormozit.naparnik.chatPage"; //$NON-NLS-1$
        private static final String ACCESS_RESPONSE = "tormozit.naparnik.accessResponse"; //$NON-NLS-1$
        private static final Pattern DUPLICATE = Pattern.compile(
            "\"error_type\"\\s*:\\s*\"duplicate_token_usage\""); //$NON-NLS-1$
        private static final Pattern RETRY = Pattern.compile("Retry in ([0-9]+) seconds"); //$NON-NLS-1$
        private static Job pending;
        private static boolean running;
        private static final String SERVICE_NOTIFY = "tormozit.naparnik.serviceNotify";
        private static final String SERVICE_PENDING = "tormozit.naparnik.servicePending";
        private static final Map<Object, Object> stoppedServices = new WeakHashMap<>();
        private static final AtomicBoolean checkingAccess = new AtomicBoolean();
        private static final String STATUS_MENU = "tormozit.naparnik.statusMenu"; //$NON-NLS-1$
        private static final String STATUS_CHECK = "tormozit.naparnik.statusCheck"; //$NON-NLS-1$
        private static Display menuDisplay;
        private static final Listener STATUS_MENU_LISTENER = ServiceRecovery::statusMenuShown;
        private static final Map<String, AccessFailure> failures = new HashMap<>();
        private static final Map<Object, Boolean> chatListeners = Collections.synchronizedMap(new WeakHashMap<>());
        private static final Map<Object, Boolean> chatCheckLinks = Collections.synchronizedMap(new WeakHashMap<>());
        private static record AccessFailure(int status, boolean duplicate, long retryAt) { }

        static synchronized void start()
        {
            running = true;
            Object early;
            Object earlyStates;
            var properties = System.getProperties();
            synchronized (properties)
            {
                properties.put(NOTIFY, (Function<Object[], Object>) ServiceRecovery::response);
                properties.put(TOKEN_MESSAGE, (Function<Object[], String>) ServiceRecovery::validationMessage);
                properties.put(CHAT_PAGE, (Function<Object[], String>) ServiceRecovery::loadingPage);
                properties.put(ACCESS_RESPONSE, (Consumer<Object>) ServiceRecovery::httpResponse);
                early = properties.remove(PENDING);
                properties.put(SERVICE_NOTIFY, (Consumer<Object[]>) ServiceRecovery::serviceState);
                earlyStates = properties.remove(SERVICE_PENDING);
            }
            if (early instanceof Object[] event)
                response(event);
            if (earlyStates instanceof java.util.List<?> events)
                for (Object event : events)
                    if (event instanceof Object[] state)
                        serviceState(state);
        }

        static synchronized void stop()
        {
            running = false;
            if (menuDisplay != null && !menuDisplay.isDisposed())
            {
                Display display = menuDisplay;
                display.asyncExec(() ->
                {
                    if (!display.isDisposed())
                        display.removeFilter(SWT.Show, STATUS_MENU_LISTENER);
                });
            }
            menuDisplay = null;
            System.getProperties().remove(NOTIFY);
            System.getProperties().remove(TOKEN_MESSAGE);
            System.getProperties().remove(CHAT_PAGE);
            System.getProperties().remove(ACCESS_RESPONSE);
            if (pending != null)
                pending.cancel();
            pending = null;
            System.getProperties().remove(SERVICE_NOTIFY);
            System.getProperties().remove(SERVICE_PENDING);
            stoppedServices.clear();
            failures.clear();
            chatListeners.clear();
            chatCheckLinks.clear();
        }

        /** Стадия наблюдения завершается до штатного boolean и диалога, ключ сопоставляется точно. */
        private static Object response(Object[] event)
        {
            if (event == null || event.length != 3 || !(event[1] instanceof String token)
                || !(event[2] instanceof CompletableFuture<?> future))
                throw new IllegalArgumentException("Invalid token-check event"); //$NON-NLS-1$
            return future.whenComplete((result, failure) ->
            {
                try
                {
                    if (result instanceof HttpResponse<?> http && http.body() instanceof String body)
                        received(event[0], token, http.statusCode(), body, System.currentTimeMillis());
                }
                catch (RuntimeException problem)
                {
                }
            });
        }

        private static synchronized void received(Object checker, String requestToken, int status, String body, long received)
        {
            boolean duplicate = DUPLICATE.matcher(body).find();
            rememberFailure(requestToken, status, body, received);
            if (!running || !duplicate)
                return;
            try
            {
                var retry = RETRY.matcher(body);
                Object settings = Global.getField(checker, "settings"); //$NON-NLS-1$
                Object currentToken = Global.invoke(settings, "getClientToken"); //$NON-NLS-1$
                if (!retry.find() || !(currentToken instanceof String token) || token.isBlank()
                    || !token.equals(requestToken))
                {
                    return;
                }
                long seconds = Long.parseLong(retry.group(1));
                // Сервер сообщает целые секунды: ждём также остаток последней секунды.
                long due = Math.addExact(received, Math.multiplyExact(Math.addExact(seconds, 1), 1_000L));
                long delay = Math.max(0, due - System.currentTimeMillis());
                if (pending != null)
                    pending.cancel();
                pending = new Job("Напарник: повторная проверка после освобождения ключа") //$NON-NLS-1$
                {
                    @Override
                    protected IStatus run(IProgressMonitor monitor)
                    {
                        synchronized (ServiceRecovery.class)
                        {
                            if (!running || pending != this || monitor.isCanceled())
                                return Status.CANCEL_STATUS;
                            pending = null;
                        }
                        try
                        {
                            if (!Boolean.TRUE.equals(Global.invoke(settings, "isEnabled")) //$NON-NLS-1$
                                || !token.equals(Global.invoke(settings, "getClientToken"))) //$NON-NLS-1$
                            {
                                return Status.OK_STATUS;
                            }
                            Bundle common = Platform.getBundle("com.e1c.edt.ai.ui.common"); //$NON-NLS-1$
                            if (common == null || common.getState() != Bundle.ACTIVE)
                            {
                                return Status.OK_STATUS;
                            }
                            Class<?> activator = common.loadClass("com.e1c.edt.ai.ui.BaseActivator"); //$NON-NLS-1$
                            Class<?> serviceType = common.loadClass("com.e1c.edt.ai.IPluginStatusService"); //$NON-NLS-1$
                            Object service = activator.getMethod("getInstance", Class.class).invoke(null, serviceType); //$NON-NLS-1$
                            boolean enabled = Boolean.TRUE.equals(serviceType.getMethod("isPluginEnabled").invoke(service)); //$NON-NLS-1$
                            if (!enabled)
                            {
                                serviceType.getMethod("revalidate").invoke(service); //$NON-NLS-1$
                            }
                        }
                        catch (Exception failure)
                        {
                        }
                        return Status.OK_STATUS;
                    }
                };
                pending.setSystem(true);
                pending.schedule(delay);

            }
            catch (RuntimeException failure)
            {
            }
        }

        private static void httpResponse(Object event)
        {
            if (!(event instanceof HttpResponse<?> response))
                return;
            if (response.statusCode() != 403 || !(response.body() instanceof String body))
                return;
            String token = response.request() != null ? response.request().headers().firstValue("Authorization").orElse(null) : null; //$NON-NLS-1$
            if (token != null)
                rememberFailure(token, response.statusCode(), body, System.currentTimeMillis());
        }

        private static synchronized void rememberFailure(String token, int status, String body, long received)
        {
            if (!running)
                return;
            if (status >= 400)
            {
                boolean duplicate = DUPLICATE.matcher(body).find();
                long retryAt = 0;
                var interval = RETRY.matcher(body);
                if (duplicate && interval.find())
                    try { retryAt = Math.addExact(received, Math.multiplyExact(Math.addExact(Long.parseLong(interval.group(1)), 1), 1_000L)); }
                    catch (ArithmeticException | NumberFormatException invalid) {  } //$NON-NLS-1$
                failures.put(token, new AccessFailure(status, duplicate, retryAt));
                refreshChatViews();
            }
            else if (status >= 200 && status < 300)
                failures.remove(token);
        }

        private static synchronized String failureText(String token)
        {
            AccessFailure failure = token != null ? failures.get(token) : null;
            if (failure == null)
                return null;
            if (!failure.duplicate())
                return "Сервер вернул ошибку HTTP " + failure.status() + ". Проверьте ключ доступа и настройки сервиса."; //$NON-NLS-1$
            long seconds = Math.max(0, (failure.retryAt() - System.currentTimeMillis() + 999) / 1_000);
            return "Ключ доступа уже используется на другом устройстве (HTTP 403).\n" //$NON-NLS-1$
                + "Закройте сеансы Напарника на других устройствах.\n" //$NON-NLS-1$
                + (seconds > 0 ? "Повторите проверку через " + seconds + " с." : "Повторите проверку доступа."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        private static String validationMessage(Object[] event)
        {
            Object token = Global.invoke(event[0], "getStringValue"); //$NON-NLS-1$
            String reason = token instanceof String s ? failureText(s) : null;
            return reason != null ? reason : (String) event[1];
        }

        private static String chatFailure(Object chat)
        {
            Object settings = Global.getField(chat, "settings"); //$NON-NLS-1$
            Object token = Global.invoke(settings, "getClientToken"); //$NON-NLS-1$
            return token instanceof String s ? failureText(s) : null;
        }

        private static String loadingPage(Object[] event)
        {
            String original = (String) event[1];
            String html = original.replace("<body>", "<body data-comfort-naparnik-loading=\"true\">"); //$NON-NLS-1$ //$NON-NLS-2$
            String reason = chatFailure(event[0]);
            if (reason == null)
                return html;
            refreshChatViews();
            String escaped = reason.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            return Pattern.compile("<div class=\"message\">.*?</div>", Pattern.DOTALL).matcher(html) //$NON-NLS-1$
                .replaceFirst(Matcher.quoteReplacement("<div class=\"message\" style=\"white-space:pre-wrap\">" + escaped + "</div>")) //$NON-NLS-1$ //$NON-NLS-2$
                .replace("</body>", "<p><a id=\"comfort-naparnik-check\" href=\"#\">Проверить ключ доступа</a></p></body>") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("<div class=\"spinner\"></div>", ""); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Уже открытая страница тоже обновляется, если 403 пришёл после создания WebView. */
        private static void refreshChatViews()
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                if (!PlatformUI.isWorkbenchRunning())
                    return;
                for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                    for (IWorkbenchPage page : window.getPages())
                    {
                        Object view = page.findView("com.e1c.edt.ai.ui.views.ChatView"); //$NON-NLS-1$
                        Object chat = view != null ? Global.getField(view, "chatDialog") : null; //$NON-NLS-1$
                        Object webView = chat != null ? Global.getField(chat, "webView") : null; //$NON-NLS-1$
                        if (webView != null)
                            try
                            {
                                Class<?> fx = Class.forName("javafx.application.Platform"); //$NON-NLS-1$
                                fx.getMethod("runLater", Runnable.class).invoke(null, (Runnable) () -> observeChatPage(chat, webView)); //$NON-NLS-1$
                            }
                            catch (Exception failure)
                            {
                            }
                    }
            });
        }

        private static void observeChatPage(Object chat, Object webView)
        {
            Object engine = Global.invoke(webView, "getEngine"); //$NON-NLS-1$
            if (engine == null)
                return;
            if (chatListeners.putIfAbsent(engine, Boolean.TRUE) == null)
                try
                {
                    Class<?> listenerType = Class.forName("javafx.beans.value.ChangeListener"); //$NON-NLS-1$
                    WeakReference<Object> chatRef = new WeakReference<>(chat);
                    WeakReference<Object> engineRef = new WeakReference<>(engine);
                    Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] { listenerType },
                        (self, method, args) ->
                        {
                            if (method.getDeclaringClass() == Object.class)
                                return switch (method.getName())
                                {
                                    case "hashCode" -> System.identityHashCode(self); //$NON-NLS-1$
                                    case "equals" -> self == args[0]; //$NON-NLS-1$
                                    default -> "NaparnikAccessStateListener"; //$NON-NLS-1$
                                };
                            Object currentChat = chatRef.get();
                            Object currentEngine = engineRef.get();
                            if (currentChat != null && currentEngine != null)
                                updateChatPage(currentChat, currentEngine);
                            return null;
                        });
                    Object worker = Global.invoke(engine, "getLoadWorker"); //$NON-NLS-1$
                    Object property = Global.invoke(worker, "stateProperty"); //$NON-NLS-1$
                    Global.invokeVoid(property, "addListener", listener); //$NON-NLS-1$
                }
                catch (Exception failure)
                {
                    chatListeners.remove(engine);
                }
            updateChatPage(chat, engine);
        }

        private static synchronized void updateChatPage(Object chat, Object engine)
        {
            String reason = running ? chatFailure(chat) : null;
            if (reason == null)
                return;
            String text = reason.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            String script = "(function(text){var b=document.querySelector('body[data-comfort-naparnik-loading]');" //$NON-NLS-1$
                + "if(!b)return false;var m=b.querySelector('.message');if(!m)return false;" //$NON-NLS-1$
                + "m.textContent=text;m.style.whiteSpace='pre-wrap';var s=b.querySelector('.spinner');" //$NON-NLS-1$
                + "if(s)s.style.display='none';if(!b.querySelector('#comfort-naparnik-check')){"
                + "var p=document.createElement('p'),a=document.createElement('a');a.id='comfort-naparnik-check';"
                + "a.href='#';a.textContent='Проверить ключ доступа';p.appendChild(a);b.appendChild(p);}"
                + "return true;})('" + text + "')"; //$NON-NLS-1$ //$NON-NLS-2$
            Object applied = Global.invoke(engine, "executeScript", script); //$NON-NLS-1$
            if (Boolean.TRUE.equals(applied))
                bindChatCheckLink(chat, engine);
        }

        /** Обработчик DOM действует только на нашей ссылке страницы недоступности. */
        private static void bindChatCheckLink(Object chat, Object engine)
        {
            Object document = Global.invoke(engine, "getDocument"); //$NON-NLS-1$
            if (!(document instanceof org.w3c.dom.Document dom))
                return;
            Object link = dom.getElementById("comfort-naparnik-check"); //$NON-NLS-1$
            if (!(link instanceof org.w3c.dom.events.EventTarget target)
                || chatCheckLinks.putIfAbsent(link, Boolean.TRUE) != null)
                return;
            Object service = Global.getField(chat, "pluginStatusService"); //$NON-NLS-1$
            target.addEventListener("click", event ->
            {
                event.preventDefault();
                Display display = Display.getDefault();
                if (service != null && display != null && !display.isDisposed())
                    display.asyncExec(() ->
                    {
                        if (running)
                            checkAccess(service);
                    });
            }, false);
        }

        /** Один показ за период недоступности; успешное включение разрешает новый показ. */
        private static synchronized void serviceState(Object[] event)
        {
            try
            {
                if (event == null || event.length != 3)
                    return;
                Object service = event[0];
                boolean enabled = Boolean.TRUE.equals(event[1]);
                String policy = readPolicyUncached();
                if (enabled)
                {
                    stoppedServices.remove(service);
                    return;
                }
                Object settings = Global.getField(service, "settings");
                if (!running || "off".equalsIgnoreCase(policy)
                    || Boolean.FALSE.equals(Global.invoke(settings, "isEnabled")))
                {
                    stoppedServices.remove(service);
                    return;
                }
                if (stoppedServices.containsKey(service))
                    return;
                Object episode = new Object();
                stoppedServices.put(service, episode);
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                    display.asyncExec(() -> showServiceNotice(display, service, episode, event[2]));
            }
            catch (RuntimeException failure)
            {
            }
        }

        private static synchronized void showServiceNotice(Display display, Object service, Object episode, Object failure)
        {
            if (!running || display.isDisposed() || stoppedServices.get(service) != episode)
                return;
            if (!PlatformUI.isWorkbenchRunning()
                || PlatformUI.getWorkbench().getWorkbenchWindows().length == 0)
            {
                display.timerExec(500, () -> showServiceNotice(display, service, episode, failure));
                return;
            }
            Object settings = Global.getField(service, "settings");
            if (PlatformUI.getWorkbench().isClosing() || "off".equalsIgnoreCase(readPolicyUncached())
                || Boolean.FALSE.equals(Global.invoke(settings, "isEnabled"))
                || Boolean.TRUE.equals(Global.invoke(service, "isPluginEnabled")))
            {
                stoppedServices.remove(service);
                return;
            }
            Object token = Global.invoke(settings, "getClientToken");
            String reason = token instanceof String key ? failureText(key) : null;
            if (failure instanceof Throwable problem)
            {
                while ((problem instanceof java.util.concurrent.CompletionException
                    || problem instanceof java.util.concurrent.ExecutionException) && problem.getCause() != null)
                    problem = problem.getCause();
                reason = problem instanceof java.net.http.HttpTimeoutException
                    ? "Сервер не ответил вовремя. Проверьте соединение и повторите проверку ключа доступа."
                    : "Не удалось проверить доступ к серверу: " + problem.getClass().getSimpleName()
                        + ". Проверьте соединение и настройки Напарника.";
            }
            if (reason == null)
                reason = token instanceof String key && key.isBlank()
                    ? "Не задан ключ доступа. Укажите его в параметрах 1С:Напарника."
                    : "Служба отключилась. Причина не передана службой; выполните проверку ключа доступа в параметрах 1С:Напарника.";
            ToastNotification.show("Служба 1С:Напарника недоступна", reason, 10_000,
                () -> checkAccess(service), "Проверить ключ доступа");
        }

        static void installStatusMenu(Display display)
        {
            if (display == null || display.isDisposed() || menuDisplay == display)
                return;
            menuDisplay = display;
            display.addFilter(SWT.Show, STATUS_MENU_LISTENER);
        }

        private static void statusMenuShown(Event event)
        {
            if (!running || !(event.widget instanceof Menu menu) || !Boolean.TRUE.equals(menu.getData(STATUS_MENU)))
                return;
            if (Boolean.TRUE.equals(menu.getData(STATUS_CHECK)))
                return;
            MenuItem check = new MenuItem(menu, SWT.PUSH, 0);
            check.setText("Проверить");
            ComfortSubmenuHelper.setMenuItemTooltip(check,
                "Проверить ключ доступа 1С:Напарника без открытия параметров");
            check.addListener(SWT.Selection, selected ->
            {
                try
                {
                    Bundle common = Platform.getBundle("com.e1c.edt.ai.ui.common"); //$NON-NLS-1$
                    Class<?> activator = common.loadClass("com.e1c.edt.ai.ui.BaseActivator"); //$NON-NLS-1$
                    Class<?> serviceType = common.loadClass("com.e1c.edt.ai.IPluginStatusService"); //$NON-NLS-1$
                    Object service = activator.getMethod("getInstance", Class.class).invoke(null, serviceType); //$NON-NLS-1$
                    checkAccess(service);
                }
                catch (Exception failure)
                {
                    ToastNotification.show("Проверка ключа 1С:Напарника", "Не удалось запустить проверку ключа доступа.", 10_000);
                }
            });
            menu.setData(STATUS_CHECK, Boolean.TRUE);
        }

        /** Те же SETTINGS_CHANGED и ITokenCheck, что у кнопки «Проверить», без окна параметров. */
        private static void checkAccess(Object service)
        {
            if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing()
                || !checkingAccess.compareAndSet(false, true))
                return;
            Display display = Display.getDefault();
            Object settings = Global.getField(service, "settings"); //$NON-NLS-1$
            Object checker = Global.getField(service, "tokenCheck"); //$NON-NLS-1$
            Object token = Global.invoke(settings, "getClientToken"); //$NON-NLS-1$
            try
            {
                if (!(token instanceof String key) || key.isBlank() || checker == null)
                    throw new IllegalStateException("Не задан ключ доступа или недоступна служба проверки.");
                Bundle common = Platform.getBundle("com.e1c.edt.ai.ui.common"); //$NON-NLS-1$
                Class<?> activator = common.loadClass("com.e1c.edt.ai.ui.BaseActivator"); //$NON-NLS-1$
                Class<?> stateType = common.loadClass("com.e1c.edt.ai.IStateService"); //$NON-NLS-1$
                Class<?> stateEnum = common.loadClass("com.e1c.edt.ai.ServiceState"); //$NON-NLS-1$
                Object stateService = activator.getMethod("getInstance", Class.class).invoke(null, stateType); //$NON-NLS-1$
                stateType.getMethod("setState", stateEnum).invoke(stateService,
                    stateEnum.getField("SETTINGS_CHANGED").get(null)); //$NON-NLS-1$ //$NON-NLS-2$
                CompletableFuture.supplyAsync(() ->
                {
                    Object result = Global.invoke(checker, "checkTokenAsync", key); //$NON-NLS-1$
                    if (!(result instanceof CompletableFuture<?> future))
                        throw new IllegalStateException("Служба проверки не вернула результат.");
                    return future;
                }).thenCompose(future -> future).whenComplete((valid, failure) ->
                {
                    checkingAccess.set(false);
                    if (!display.isDisposed())
                        display.asyncExec(() ->
                        {
                            if (!running || !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing()
                                || !key.equals(Global.invoke(settings, "getClientToken"))) //$NON-NLS-1$
                                return;
                            String reason = Boolean.TRUE.equals(valid) ? "Ключ доступа в порядке." : failureText(key);
                            if (failure != null)
                            {
                                Throwable cause = failure;
                                while (cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException
                                    || cause instanceof java.util.concurrent.ExecutionException))
                                    cause = cause.getCause();
                                reason = "Не удалось проверить ключ доступа: " + cause.getClass().getSimpleName() + ".";
                            }
                            ToastNotification.show("Проверка ключа 1С:Напарника",
                                reason != null ? reason : "Ключ доступа не принят сервером.", 10_000);
                        });
                });
            }
            catch (Exception failure)
            {
                checkingAccess.set(false);
                ToastNotification.show("Проверка ключа 1С:Напарника", "Не удалось запустить проверку ключа доступа.", 10_000);
            }
        }
    }
}
