package tormozit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.jobs.Job;
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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * В режиме «Ручной» 1С:Напарник не меняет модуль без явной команды
 * («Предложи код», Tab / Ctrl+Enter). Штатный ViewModel после вставки вызывает
 * {@code askNew}; фактическая запись в документ — {@code CodeCompletionContext.replace}.
 *
 * @see <a href="https://github.com/tormozit/EDT.Comfort/issues/378">issue 378</a>
 */
public final class NaparnikManualModeHook implements IStartup
{
    static final String PROP_ASK_NEW = "tormozit.naparnik.askNew"; //$NON-NLS-1$
    static final String PROP_DOC_CHANGED = "tormozit.naparnik.documentChanged"; //$NON-NLS-1$
    static final String PROP_VERIFY_KEY = "tormozit.naparnik.verifyKey"; //$NON-NLS-1$
    static final String PROP_REPLACE = "tormozit.naparnik.replace"; //$NON-NLS-1$
    static final String PROP_HINT = "tormozit.naparnik.hint"; //$NON-NLS-1$

    private static final String LOG = "naparnik-378"; //$NON-NLS-1$
    private static final String TAG = "NaparnikManualMode"; //$NON-NLS-1$
    private static final String PREF_NODE = "com.e1c.edt.ai.ui"; //$NON-NLS-1$
    private static final String PREF_KEY = "stringPreferenceCodeCompletionPolicy"; //$NON-NLS-1$

    private static final String VM = "com.e1c.edt.ai.ui.CodeCompletionViewModel"; //$NON-NLS-1$
    private static final String VM_INTERNAL = "com/e1c/edt/ai/ui/CodeCompletionViewModel"; //$NON-NLS-1$
    private static final String CTX = "com.e1c.edt.ai.ui.CodeCompletionContext"; //$NON-NLS-1$
    private static final String CTX_INTERNAL = "com/e1c/edt/ai/ui/CodeCompletionContext"; //$NON-NLS-1$
    private static final String PAINTER = "com.e1c.edt.ai.ui.HintPainter"; //$NON-NLS-1$
    private static final String PAINTER_INTERNAL = "com/e1c/edt/ai/ui/HintPainter"; //$NON-NLS-1$

    private static final String CLIPBOARD_DESC = "Lcom/e1c/edt/ai/ui/IClipboard;"; //$NON-NLS-1$
    private static final String DOCUMENT_CHANGED_DESC =
        "(Lorg/eclipse/jface/text/DocumentEvent;)V"; //$NON-NLS-1$
    private static final String VERIFY_KEY_DESC = "(Lorg/eclipse/swt/events/VerifyEvent;)V"; //$NON-NLS-1$
    private static final String REPLACE_DESC = "(IILjava/lang/String;)V"; //$NON-NLS-1$
    private static final String SET_HINT_DESC = "(Ljava/lang/String;Ljava/lang/String;I)V"; //$NON-NLS-1$

    private static final String CMD_PASTE = "org.eclipse.ui.edit.paste"; //$NON-NLS-1$
    private static final String CMD_SUGGEST = "com.e1c.edt.ai.ui.commands.suggest.ai"; //$NON-NLS-1$
    private static final String CMD_ACCEPT = "com.e1c.edt.ai.ui.commands.accept.ai"; //$NON-NLS-1$
    private static final String CMD_ACCEPT_PART = "com.e1c.edt.ai.ui.commands.acceptpart.ai"; //$NON-NLS-1$
    private static final String CMD_ACCEPT_LINE = "com.e1c.edt.ai.ui.commands.acceptline.ai"; //$NON-NLS-1$
    private static final String CMD_DOC_COMMENTS =
        "com.e1c.edt.ai.ui.commands.generatedoccomments.ai"; //$NON-NLS-1$

    private static final int MAX_ATTACH_ATTEMPTS = 100;
    private static final int[] SUPPRESS_DELAYS_MS = { 0, 50, 150, 400, 1000, 2500 };

    private static final AtomicInteger allowApply = new AtomicInteger();
    private static final AtomicInteger allowSuggest = new AtomicInteger();
    private static final AtomicInteger pasteActive = new AtomicInteger();
    private static final AtomicBoolean reverting = new AtomicBoolean();
    private static final Map<IDocument, DocumentGuard> guards = new WeakHashMap<>();
    private static final IdentityHashMap<DtGranularEditor<?>, Boolean> granularHooked =
        new IdentityHashMap<>();

    @Override
    public void earlyStartup()
    {
        log("earlyStartup begin thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
        System.getProperties().put(PROP_ASK_NEW, (Runnable) NaparnikManualModeHook::onAskNew);
        System.getProperties().put(PROP_DOC_CHANGED,
            (Consumer<Object>) NaparnikManualModeHook::onViewModelDocumentChanged);
        System.getProperties().put(PROP_VERIFY_KEY,
            (Consumer<Object>) NaparnikManualModeHook::onViewModelVerifyKey);
        System.getProperties().put(PROP_REPLACE,
            (Predicate<Object>) NaparnikManualModeHook::allowReplace);
        System.getProperties().put(PROP_HINT, (Predicate<Object>) NaparnikManualModeHook::allowHint);

        registerWeavingHook();
        registerTransformer();
        logPolicy("startup"); //$NON-NLS-1$

        Display display = Display.getDefault();
        if (display == null)
        {
            log("Display.getDefault()=null"); //$NON-NLS-1$
            Global.flushTempLogs();
            return;
        }
        display.asyncExec(NaparnikManualModeHook::installUi);
    }

    private static void installUi()
    {
        log("installUi"); //$NON-NLS-1$
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
            {
                log("workbench not running"); //$NON-NLS-1$
                return;
            }
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null)
                commands.addExecutionListener(COMMAND_LISTENER);
            else
                log("ICommandService=null"); //$NON-NLS-1$

            Display display = Display.getCurrent();
            if (display != null)
            {
                Listener verify = NaparnikManualModeHook::onDisplayVerify;
                display.addFilter(SWT.Verify, verify);
                display.addFilter(ST.VerifyKey, verify);
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
            log("installUi done windows=" //$NON-NLS-1$
                + PlatformUI.getWorkbench().getWorkbenchWindows().length);
        }
        catch (Throwable t)
        {
            Global.tempLogException(LOG, "installUi", t); //$NON-NLS-1$
        }
        Global.flushTempLogs();
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
        attachDocument(viewer.getDocument(), editor.getClass().getSimpleName());
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
        attachDocument(viewer.getDocument(), editor.getClass().getSimpleName());
    }

    private static void attachDocument(IDocument document, String editorKind)
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
        log("attach doc editor=" + editorKind + " len=" + document.getLength()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    static void onAskNew()
    {
        log("ASM askNew policy=" + readPolicy() + " restricted=" + isRestrictedPolicy() //$NON-NLS-1$ //$NON-NLS-2$
            + " paste=" + pasteActive.get() + " apply=" + allowApply.get() //$NON-NLS-1$ //$NON-NLS-2$
            + " suggest=" + allowSuggest.get() + stack()); //$NON-NLS-1$
        Global.flushTempLogs();
    }

    static void onViewModelDocumentChanged(Object event)
    {
        String preview = ""; //$NON-NLS-1$
        if (event instanceof DocumentEvent de)
            preview = preview(de.getText());
        log("ASM documentChanged preview=" + preview + " paste=" + pasteActive.get() //$NON-NLS-1$ //$NON-NLS-2$
            + " policy=" + readPolicy() + stack()); //$NON-NLS-1$
        Global.flushTempLogs();
    }

    static void onViewModelVerifyKey(Object event)
    {
        log("ASM verifyKey " + describeSwt(event) + " policy=" + readPolicy() + stack()); //$NON-NLS-1$ //$NON-NLS-2$
        Global.flushTempLogs();
    }

    static boolean allowReplace(Object text)
    {
        boolean allow = !isRestrictedPolicy() || allowApply.get() > 0;
        log("ASM replace allow=" + allow + " restricted=" + isRestrictedPolicy() //$NON-NLS-1$ //$NON-NLS-2$
            + " apply=" + allowApply.get() + " preview=" + preview(String.valueOf(text)) + stack()); //$NON-NLS-1$ //$NON-NLS-2$
        Global.flushTempLogs();
        return allow;
    }

    static boolean allowHint(Object text)
    {
        boolean allow = !isRestrictedPolicy() || allowSuggest.get() > 0 || allowApply.get() > 0;
        log("ASM setHintAt allow=" + allow + " restricted=" + isRestrictedPolicy() //$NON-NLS-1$ //$NON-NLS-2$
            + " suggest=" + allowSuggest.get() + " apply=" + allowApply.get() //$NON-NLS-1$ //$NON-NLS-2$
            + " preview=" + preview(String.valueOf(text))); //$NON-NLS-1$
        Global.flushTempLogs();
        return allow;
    }

    /**
     * Display-фильтр ловит {@code SWT.Verify} любого текстового виджета. Сброс ViewModel
     * Напарника нужен только при вставке в модуль: иначе заполнение фильтра панели, журнала
     * или «Недавних мест» вызывает {@code reset()} у активного редактора и мигает линейка.
     */
    private static void onDisplayVerify(Event event)
    {
        if (event == null || !isHookedEditorStyledText(event.widget))
            return;
        String text = event.text;
        boolean multi = text != null && text.length() > 1;
        if (isAcceptHotkey(event))
        {
            allowApply.incrementAndGet();
            log("verify accept-hotkey " + describeSwt(event)); //$NON-NLS-1$
            Display display = event.display != null ? event.display : Display.getCurrent();
            if (display != null)
                display.timerExec(800, () -> allowApply.updateAndGet(v -> Math.max(0, v - 1)));
        }
        if (isSuggestHotkey(event))
        {
            allowSuggest.incrementAndGet();
            log("verify suggest-hotkey " + describeSwt(event)); //$NON-NLS-1$
        }
        if (multi || isAcceptHotkey(event) || isSuggestHotkey(event))
        {
            log("display verify " + describeSwt(event) + " preview=" + preview(text)); //$NON-NLS-1$ //$NON-NLS-2$
            if (multi && isRestrictedPolicy())
                scheduleSuppress(null);
        }
    }

    private static final IExecutionListener COMMAND_LISTENER = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (commandId == null)
                return;
            boolean naparnik = commandId.startsWith("com.e1c.edt.ai"); //$NON-NLS-1$
            boolean paste = CMD_PASTE.equals(commandId);
            if (!naparnik && !paste)
                return;
            log("cmd pre " + commandId + " policy=" + readPolicy()); //$NON-NLS-1$ //$NON-NLS-2$
            if (paste)
            {
                Display display = Display.getCurrent();
                Control focus = display != null ? display.getFocusControl() : null;
                if (isHookedEditorStyledText(focus))
                {
                    pasteActive.incrementAndGet();
                    allowSuggest.set(0);
                    if (isRestrictedPolicy())
                        scheduleSuppress(null);
                }
            }
            if (CMD_SUGGEST.equals(commandId))
                allowSuggest.incrementAndGet();
            if (CMD_ACCEPT.equals(commandId) || CMD_ACCEPT_PART.equals(commandId)
                || CMD_ACCEPT_LINE.equals(commandId))
                allowApply.incrementAndGet();
            if (CMD_DOC_COMMENTS.equals(commandId))
                log("cmd generatedoccomments " + stack()); //$NON-NLS-1$
            Global.flushTempLogs();
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
        if (CMD_SUGGEST.equals(commandId))
        {
            /* оставляем до вставки из буфера: подсказка приходит асинхронно */
        }
        if (CMD_ACCEPT.equals(commandId) || CMD_ACCEPT_PART.equals(commandId)
            || CMD_ACCEPT_LINE.equals(commandId))
        {
            Display display = Display.getCurrent();
            if (display != null)
                display.timerExec(800, () -> allowApply.updateAndGet(v -> Math.max(0, v - 1)));
            else
                allowApply.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    private static void scheduleSuppress(IDocument document)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        for (int delay : SUPPRESS_DELAYS_MS)
        {
            display.timerExec(delay, () ->
            {
                if (!isRestrictedPolicy() || allowApply.get() > 0 || allowSuggest.get() > 0)
                    return;
                suppressViewModel(document);
            });
        }
    }

    private static void suppressViewModel(IDocument document)
    {
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
        if (found.isEmpty())
        {
            log("suppress: ViewModel not found doc=" + (document != null) + " widget=" //$NON-NLS-1$ //$NON-NLS-2$
                + (widget != null));
            return;
        }
        for (Object vm : found)
        {
            Object balanced = Global.invoke(vm, "isBalanced"); //$NON-NLS-1$
            log("suppress reset vm=" + vm.getClass().getName() + " balanced=" + balanced); //$NON-NLS-1$ //$NON-NLS-2$
            if (Boolean.TRUE.equals(balanced))
                continue;
            Global.invokeVoid(vm, "reset"); //$NON-NLS-1$
            cancelJob(vm, "lastJob"); //$NON-NLS-1$
            cancelJob(vm, "commitJob"); //$NON-NLS-1$
        }
        Global.flushTempLogs();
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

    private static void cancelJob(Object vm, String field)
    {
        Object job = Global.getField(vm, field);
        if (job instanceof Job j)
            j.cancel();
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
            String st = stack();
            boolean naparnik = st.contains("com.e1c.edt.ai"); //$NON-NLS-1$
            boolean interesting = naparnik || inserted.length() > 1 || pasteActive.get() > 0;
            if (interesting)
            {
                log("doc offset=" + event.getOffset() + " rm=" + event.getLength() //$NON-NLS-1$ //$NON-NLS-2$
                    + " add=" + inserted.length() + " naparnik=" + naparnik //$NON-NLS-1$ //$NON-NLS-2$
                    + " paste=" + pasteActive.get() + " apply=" + allowApply.get() //$NON-NLS-1$ //$NON-NLS-2$
                    + " policy=" + readPolicy() + " preview=" + preview(inserted) //$NON-NLS-1$ //$NON-NLS-2$
                    + (naparnik || inserted.length() > 20 ? st : "")); //$NON-NLS-1$
                Global.flushTempLogs();
            }
            if (isRestrictedPolicy() && (pasteActive.get() > 0 || inserted.length() > 1))
                scheduleSuppress(event.getDocument());
            if (!isRestrictedPolicy() || !naparnik || allowApply.get() > 0 || pasteActive.get() > 0)
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
                    log("undo naparnik insert offset=" + offset + " add=" + inserted.length()); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (Exception e)
                {
                    Global.tempLogException(LOG, "undo", e); //$NON-NLS-1$
                }
                finally
                {
                    reverting.set(false);
                    Global.flushTempLogs();
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

    private static String readPolicy()
    {
        String[] nodes = { PREF_NODE, "com.e1c.edt.ai", "com.e1c.edt.ai.ui.common" }; //$NON-NLS-1$ //$NON-NLS-2$
        for (String node : nodes)
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

    private static void logPolicy(String phase)
    {
        StringBuilder sb = new StringBuilder(phase).append(" policyNodes"); //$NON-NLS-1$
        for (String node : new String[] { PREF_NODE, "com.e1c.edt.ai", "com.e1c.edt.ai.ui.common" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String value = null;
            try
            {
                value = InstanceScope.INSTANCE.getNode(node).get(PREF_KEY, null);
            }
            catch (Exception e)
            {
                value = e.getClass().getSimpleName();
            }
            sb.append(" [").append(node).append("=").append(value).append("]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        log(sb.toString());
    }

    private static void registerWeavingHook()
    {
        try
        {
            Bundle bundle = FrameworkUtil.getBundle(NaparnikManualModeHook.class);
            BundleContext context = bundle != null ? bundle.getBundleContext() : null;
            if (context == null)
            {
                log("WeavingHook: bundleContext=null"); //$NON-NLS-1$
                return;
            }
            context.registerService(WeavingHook.class, new ManualWeavingHook(), null);
            log("WeavingHook registered"); //$NON-NLS-1$
        }
        catch (Throwable t)
        {
            Global.tempLogException(LOG, "WeavingHook", t); //$NON-NLS-1$
        }
    }

    private static void registerTransformer()
    {
        ManualModeTransformer transformer = new ManualModeTransformer();
        boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(transformer, VM, CTX, PAINTER);
        log("registerExtraTransformer=" + ok); //$NON-NLS-1$
        logLoaded(VM);
        logLoaded(CTX);
        logLoaded(PAINTER);
        retransformLoaded();
    }

    private static void logLoaded(String name)
    {
        try
        {
            Class<?> c = Class.forName(name, false, NaparnikManualModeHook.class.getClassLoader());
            log("loaded " + name + " loader=" + c.getClassLoader()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (ClassNotFoundException e)
        {
            log("not loaded yet " + name); //$NON-NLS-1$
        }
        catch (Throwable t)
        {
            Global.tempLogException(LOG, "logLoaded " + name, t); //$NON-NLS-1$
        }
    }

    private static void retransformLoaded()
    {
        try
        {
            Field field = BslDocCommentDescriptionFix.class.getDeclaredField("instrumentation"); //$NON-NLS-1$
            field.setAccessible(true);
            Object value = field.get(null);
            log("instrumentation=" + (value != null ? value.getClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            if (!(value instanceof Instrumentation inst))
                return;
            for (Class<?> c : inst.getAllLoadedClasses())
            {
                String name = c.getName();
                if (!VM.equals(name) && !CTX.equals(name) && !PAINTER.equals(name))
                    continue;
                boolean modifiable = inst.isModifiableClass(c);
                log("retransform " + name + " modifiable=" + modifiable); //$NON-NLS-1$ //$NON-NLS-2$
                if (!modifiable)
                    continue;
                try
                {
                    inst.retransformClasses(c);
                    log("retransform ok " + name); //$NON-NLS-1$
                }
                catch (Throwable t)
                {
                    Global.tempLogException(LOG, "retransform " + name, t); //$NON-NLS-1$
                }
            }
        }
        catch (Throwable t)
        {
            Global.tempLogException(LOG, "retransformLoaded", t); //$NON-NLS-1$
        }
    }

    static byte[] transformClass(String internal, byte[] classfileBuffer)
    {
        if (VM_INTERNAL.equals(internal))
            return transformViewModel(classfileBuffer);
        if (CTX_INTERNAL.equals(internal))
            return transformReplace(classfileBuffer);
        if (PAINTER_INTERNAL.equals(internal))
            return transformHint(classfileBuffer);
        return null;
    }

    static byte[] transformViewModel(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!hasViewModelMembers(reader))
        {
            log("ViewModel hasRequiredMembers=false"); //$NON-NLS-1$
            return null;
        }
        ClassWriter writer = writer(reader);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null)
                    return null;
                if ("documentChanged".equals(name) && DOCUMENT_CHANGED_DESC.equals(descriptor)) //$NON-NLS-1$
                {
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            emitConsumer(this, PROP_DOC_CHANGED, 1);
                            emitUnbalancedReturn(this);
                            touched.set(true);
                        }
                    };
                }
                if ("askNew".equals(name) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            emitRunnable(this, PROP_ASK_NEW);
                            emitUnbalancedReturn(this);
                            touched.set(true);
                        }
                    };
                }
                if ("verifyKey".equals(name) && VERIFY_KEY_DESC.equals(descriptor)) //$NON-NLS-1$
                {
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            emitConsumer(this, PROP_VERIFY_KEY, 1);
                            touched.set(true);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);
        log("transform ViewModel touched=" + touched.get()); //$NON-NLS-1$
        return touched.get() ? writer.toByteArray() : null;
    }

    static byte[] transformReplace(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!hasMethod(reader, "replace", REPLACE_DESC)) //$NON-NLS-1$
        {
            log("Context.replace missing"); //$NON-NLS-1$
            return null;
        }
        ClassWriter writer = writer(reader);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null)
                    return null;
                if (!"replace".equals(name) || !REPLACE_DESC.equals(descriptor)) //$NON-NLS-1$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitCode()
                    {
                        super.visitCode();
                        emitPredicateReturnIfFalse(this, PROP_REPLACE, 3);
                        touched.set(true);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        log("transform Context.replace touched=" + touched.get()); //$NON-NLS-1$
        return touched.get() ? writer.toByteArray() : null;
    }

    static byte[] transformHint(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!hasMethod(reader, "setHintAt", SET_HINT_DESC)) //$NON-NLS-1$
        {
            log("HintPainter.setHintAt missing"); //$NON-NLS-1$
            return null;
        }
        ClassWriter writer = writer(reader);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null)
                    return null;
                if (!"setHintAt".equals(name) || !SET_HINT_DESC.equals(descriptor)) //$NON-NLS-1$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitCode()
                    {
                        super.visitCode();
                        emitPredicateReturnIfFalse(this, PROP_HINT, 1);
                        touched.set(true);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        log("transform HintPainter.setHintAt touched=" + touched.get()); //$NON-NLS-1$
        return touched.get() ? writer.toByteArray() : null;
    }

    private static ClassWriter writer(ClassReader reader)
    {
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                return "java/lang/Object"; //$NON-NLS-1$
            }
        };
    }

    private static void emitRunnable(MethodVisitor mv, String prop)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(prop);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Runnable"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Runnable"); //$NON-NLS-1$
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
    }

    private static void emitConsumer(MethodVisitor mv, String prop, int argLocal)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(prop);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer"); //$NON-NLS-1$
        mv.visitVarInsn(Opcodes.ALOAD, argLocal);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
    }

    private static void emitPredicateReturnIfFalse(MethodVisitor mv, String prop, int textLocal)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(prop);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Predicate"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate"); //$NON-NLS-1$
        mv.visitVarInsn(Opcodes.ALOAD, textLocal);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Z", true); //$NON-NLS-1$
        Label ok = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, ok);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(ok);
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
    }

    private static void emitUnbalancedReturn(MethodVisitor mv)
    {
        Label ok = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VM_INTERNAL, "isBalanced", "()Z", false); //$NON-NLS-1$ //$NON-NLS-2$
        mv.visitJumpInsn(Opcodes.IFNE, ok);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(ok);
    }

    private static boolean hasViewModelMembers(ClassReader reader)
    {
        AtomicBoolean clipboard = new AtomicBoolean();
        AtomicBoolean isEnabled = new AtomicBoolean();
        AtomicBoolean isBalanced = new AtomicBoolean();
        AtomicBoolean reset = new AtomicBoolean();
        AtomicBoolean documentChanged = new AtomicBoolean();
        AtomicBoolean askNew = new AtomicBoolean();
        AtomicBoolean verifyKey = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9)
        {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                String signature, Object value)
            {
                if ("clipboard".equals(name) && CLIPBOARD_DESC.equals(descriptor)) //$NON-NLS-1$
                    clipboard.set(true);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                if ("isEnabled".equals(name) && "()Z".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                    isEnabled.set(true);
                else if ("isBalanced".equals(name) && "()Z".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                    isBalanced.set(true);
                else if ("reset".equals(name) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                    reset.set(true);
                else if ("documentChanged".equals(name) && DOCUMENT_CHANGED_DESC.equals(descriptor)) //$NON-NLS-1$
                    documentChanged.set(true);
                else if ("askNew".equals(name) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                    askNew.set(true);
                else if ("verifyKey".equals(name) && VERIFY_KEY_DESC.equals(descriptor)) //$NON-NLS-1$
                    verifyKey.set(true);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return clipboard.get() && isEnabled.get() && isBalanced.get() && reset.get()
            && documentChanged.get() && askNew.get() && verifyKey.get();
    }

    private static boolean hasMethod(ClassReader reader, String methodName, String methodDesc)
    {
        AtomicBoolean found = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                if (methodName.equals(name) && methodDesc.equals(descriptor))
                    found.set(true);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found.get();
    }

    private static final class ManualModeTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!VM_INTERNAL.equals(className) && !CTX_INTERNAL.equals(className)
                && !PAINTER_INTERNAL.equals(className))
                return null;
            log("transform hit " + className + " redefine=" + (classBeingRedefined != null) //$NON-NLS-1$ //$NON-NLS-2$
                + " bytes=" + (classfileBuffer != null ? classfileBuffer.length : 0)); //$NON-NLS-1$
            try
            {
                byte[] out = transformClass(className, classfileBuffer);
                log("transform result " + className + " out=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (out != null ? out.length : "null")); //$NON-NLS-1$
                Global.flushTempLogs();
                return out;
            }
            catch (Throwable t)
            {
                Global.tempLogException(LOG, "transform " + className, t); //$NON-NLS-1$
                Global.flushTempLogs();
                return null;
            }
        }
    }

    private static final class ManualWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            String name = wovenClass.getClassName();
            if (!VM.equals(name) && !CTX.equals(name) && !PAINTER.equals(name))
                return;
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            log("weave " + name); //$NON-NLS-1$
            try
            {
                byte[] transformed = transformClass(name.replace('.', '/'), wovenClass.getBytes());
                if (transformed != null)
                {
                    wovenClass.setBytes(transformed);
                    log("weave applied " + name + " bytes=" + transformed.length); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else
                    log("weave skip " + name); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                Global.tempLogException(LOG, "weave " + name, t); //$NON-NLS-1$
            }
            Global.flushTempLogs();
        }
    }

    private static void log(String text)
    {
        Global.tempLog(LOG, text);
    }

    private static String preview(String text)
    {
        if (text == null)
            return "null"; //$NON-NLS-1$
        String one = text.replace("\r", "\\r").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (one.length() > 160)
            return one.substring(0, 160) + "…(" + text.length() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        return one;
    }

    private static String describeSwt(Object event)
    {
        if (event instanceof Event e)
            return "type=" + e.type + " key=" + e.keyCode + " ch=" + (int)e.character //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " mask=" + e.stateMask + " textLen=" //$NON-NLS-1$ //$NON-NLS-2$
                + (e.text != null ? e.text.length() : 0);
        if (event == null)
            return "null"; //$NON-NLS-1$
        Object keyCode = Global.getField(event, "keyCode"); //$NON-NLS-1$
        Object character = Global.getField(event, "character"); //$NON-NLS-1$
        Object stateMask = Global.getField(event, "stateMask"); //$NON-NLS-1$
        Object text = Global.getField(event, "text"); //$NON-NLS-1$
        return "class=" + event.getClass().getSimpleName() + " key=" + keyCode + " ch=" + character //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " mask=" + stateMask + " textLen=" //$NON-NLS-1$ //$NON-NLS-2$
            + (text instanceof String s ? s.length() : 0);
    }

    private static String stack()
    {
        StringWriter sw = new StringWriter();
        new Throwable("trace").printStackTrace(new PrintWriter(sw)); //$NON-NLS-1$
        return System.lineSeparator() + sw;
    }
}
