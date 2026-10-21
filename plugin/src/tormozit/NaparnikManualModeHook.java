package tormozit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

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
            boolean revert = PATCH_ENABLED
                && isRestrictedPolicy() && naparnik && applyLeftMs() == 0 && pasteActive.get() == 0;
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
    private static boolean stackHasNaparnik()
    {
        return STACK_WALKER.walk(
            frames -> frames.anyMatch(frame -> frame.getClassName().startsWith(NAPARNIK_PACKAGE)));
    }
}
