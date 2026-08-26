package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Кнопка «Вставить в редактор» в чате 1С:Напарник: если в редакторе есть выделение,
 * штатный вопрос «Заменить выделенный код?» не показывается — сразу окно
 * «Вставить со сравнением». Без выделения вставка идёт штатно в позицию каретки.
 *
 * <p>JS вызывает {@code window.ideApi.paste_code}. Java-{@code setMember} нашего
 * объекта LiveConnect не удерживается ({@code getMember} после него {@code null}),
 * ASM {@code IdeApiHandler} при старте часто ещё без Instrumentation. Основной путь:
 * JS-обёртка {@code window.ideApi} в WebView (текст в {@code __comfortLastPaste})
 * и перехват штатного {@code MessageDialog} — сразу сравнение, без Yes/No.
 *
 * @see <a href="https://github.com/tormozit/EDT.Comfort/issues/374">issue 374</a>
 */
public final class NaparnikPasteCompareHook implements IStartup
{
    static final String PROP_PASTE_CODE = "tormozit.naparnik.pasteCode"; //$NON-NLS-1$
    private static final String TAG = "NaparnikPasteCompare"; //$NON-NLS-1$
    private static final String TARGET = "com.e1c.edt.ai.ui.IdeApiHandler"; //$NON-NLS-1$
    private static final String TARGET_INTERNAL = "com/e1c/edt/ai/ui/IdeApiHandler"; //$NON-NLS-1$
    private static final String PASTE_DESC = "(Ljava/lang/String;)V"; //$NON-NLS-1$
    private static final String CHAT_VIEW_ID = "com.e1c.edt.ai.ui.views.ChatView"; //$NON-NLS-1$
    private static final String CHAT_VIEW_CLASS = "com.e1c.edt.ai.ui.ChatView"; //$NON-NLS-1$
    private static final String NATIVE_QUESTION_RU = "Заменить выделенный код?"; //$NON-NLS-1$
    private static final String NATIVE_QUESTION_EN = "Replace selected code?"; //$NON-NLS-1$
    private static final String WRAPPED_KEY = "tormozit.naparnikIdeApiWrapped"; //$NON-NLS-1$
    private static final String WINDOW_DATA_KEY = "org.eclipse.jface.window.Window"; //$NON-NLS-1$
    private static final String READ_PASTE_JS =
        "(function(){var t=window.__comfortLastPaste;return t==null?'':String(t);})()"; //$NON-NLS-1$
    private static final String WRAP_JS =
        "(function(){" //$NON-NLS-1$
        + "if(window.__comfortPasteGuard)return 'exists';" //$NON-NLS-1$
        + "window.__comfortPasteGuard=true;" //$NON-NLS-1$
        + "window.__comfortWrapCount=0;" //$NON-NLS-1$
        + "window.__comfortWrapErr='';" //$NON-NLS-1$
        + "var raw=window.ideApi;" //$NON-NLS-1$
        + "function wrapHandler(h){" //$NON-NLS-1$
        + "if(!h)return h;" //$NON-NLS-1$
        + "try{if(h.__comfortPaste===true)return h;}catch(e){}" //$NON-NLS-1$
        + "return{__comfortPaste:true," //$NON-NLS-1$
        + "paste_code:function(code){window.__comfortLastPaste=code==null?'':String(code);return h.paste_code(code);}," //$NON-NLS-1$
        + "wink:function(a){return h.wink(a);}," //$NON-NLS-1$
        + "callTools:function(a,b,c){return h.callTools(a,b,c);}," //$NON-NLS-1$
        + "trace:function(a){return h.trace(a);}," //$NON-NLS-1$
        + "isReady:function(){return h.isReady();}," //$NON-NLS-1$
        + "reset:function(){return h.reset();}," //$NON-NLS-1$
        + "renderTools:function(a,b,c){try{return h.renderTools(a,b,c);}catch(e){return null;}}," //$NON-NLS-1$
        + "link:function(a,b){try{return h.link(a,b);}catch(e){return false;}}};}" //$NON-NLS-1$
        + "function getter(){return wrapHandler(raw);}" //$NON-NLS-1$
        + "getter.__comfort=true;" //$NON-NLS-1$
        + "function install(){" //$NON-NLS-1$
        + "try{" //$NON-NLS-1$
        + "var d=Object.getOwnPropertyDescriptor(window,'ideApi');" //$NON-NLS-1$
        + "if(d&&d.get&&d.get.__comfort)return;" //$NON-NLS-1$
        + "if(d&&'value' in d)raw=d.value;" //$NON-NLS-1$
        + "Object.defineProperty(window,'ideApi',{configurable:true,enumerable:true,get:getter,set:function(v){raw=v;window.__comfortWrapCount++;}});" //$NON-NLS-1$
        + "}catch(e){window.__comfortWrapErr=String(e);}" //$NON-NLS-1$
        + "}" //$NON-NLS-1$
        + "install();setInterval(install,200);return 'started';" //$NON-NLS-1$
        + "})()"; //$NON-NLS-1$

    private static volatile Object lastHandler;
    private static volatile Object lastWebView;
    private static volatile boolean asmRegistered;

    @Override
    public void earlyStartup()
    {
        System.getProperties().put(PROP_PASTE_CODE,
            (BiFunction<Object, Object, Object>) NaparnikPasteCompareHook::handlePasteCode);
        registerAsm();
        Display display = Display.getDefault();
        display.asyncExec(() ->
        {
            installUi(display);
            display.timerExec(2000, NaparnikPasteCompareHook::registerAsm);
        });
    }

    private static void registerAsm()
    {
        if (asmRegistered)
            return;
        boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(
            new PasteCodeTransformer(), TARGET);
        if (ok)
            asmRegistered = true;
        else
            Global.logError(TAG, "ASM transformer for paste_code() not registered", null); //$NON-NLS-1$
    }

    private static void installUi(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener nativeQuestion = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            display.asyncExec(() -> interceptNativeReplaceQuestion(display, shell));
        };
        display.addFilter(SWT.Show, nativeQuestion);
        display.addFilter(SWT.Activate, nativeQuestion);

        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        IWindowListener windows = new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
            @Override public void windowClosed(IWorkbenchWindow window) {}
            @Override public void windowActivated(IWorkbenchWindow window) {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
        };
        workbench.addWindowListener(windows);
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        wrapAllChatViews();
        scheduleWrapRetries(display);
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference partRef)
            {
                wrapIfChatView(partRef);
            }

            @Override
            public void partActivated(IWorkbenchPartReference partRef)
            {
                wrapIfChatView(partRef);
            }
        });
    }

    private static void wrapIfChatView(IWorkbenchPartReference partRef)
    {
        if (partRef == null || !isChatView(partRef))
            return;
        IViewPart view = partRef.getPage().findView(partRef.getId());
        if (view == null && partRef instanceof IViewReference viewRef)
            view = viewRef.getView(false);
        wrapChatView(view);
    }

    private static boolean isChatView(IWorkbenchPartReference partRef)
    {
        String id = partRef.getId();
        if (CHAT_VIEW_ID.equals(id))
            return true;
        String className = partRef.getPart(false) != null
            ? partRef.getPart(false).getClass().getName()
            : ""; //$NON-NLS-1$
        return CHAT_VIEW_CLASS.equals(className);
    }

    private static void wrapAllChatViews()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                for (IViewReference ref : page.getViewReferences())
                {
                    if (!isChatView(ref))
                        continue;
                    wrapChatView(ref.getView(false));
                }
            }
        }
    }

    /** Повторная инъекция, если WebView пересоздали. Обёртку ideApi держит JS-interval. */
    private static void scheduleWrapRetries(Display display)
    {
        if (display.isDisposed())
            return;
        display.timerExec(2000, () ->
        {
            wrapAllChatViews();
            scheduleWrapRetries(display);
        });
    }

    private static void wrapChatView(IViewPart view)
    {
        if (view == null)
            return;
        try
        {
            Object chat = Global.getField(view, "chatDialog"); //$NON-NLS-1$
            if (chat == null)
                return;
            Object handler = Global.getField(chat, "handler"); //$NON-NLS-1$
            Object webView = Global.getField(chat, "webView"); //$NON-NLS-1$
            if (handler != null)
                lastHandler = handler;
            if (webView != null)
                lastWebView = webView;
            if (webView == null)
                return;
            runOnFx(() -> injectJsWrap(webView));
        }
        catch (Throwable ignored)
        {
            // чат ещё не готов
        }
    }

    private static void runOnFx(Runnable action)
    {
        try
        {
            Class<?> platform = Class.forName("javafx.application.Platform"); //$NON-NLS-1$
            Boolean fxThread = (Boolean) platform.getMethod("isFxApplicationThread").invoke(null); //$NON-NLS-1$
            if (Boolean.TRUE.equals(fxThread))
            {
                action.run();
                return;
            }
            platform.getMethod("runLater", Runnable.class).invoke(null, action); //$NON-NLS-1$
        }
        catch (Throwable ignored)
        {
            // JavaFX ещё не поднят
        }
    }

    private static void injectJsWrap(Object webView)
    {
        try
        {
            Object engine = Global.invoke(webView, "getEngine"); //$NON-NLS-1$
            if (engine == null)
                return;
            Global.invoke(engine, "executeScript", WRAP_JS); //$NON-NLS-1$
        }
        catch (Throwable ignored)
        {
            // страница чата ещё не загружена
        }
    }

    /**
     * Вызов из инструментированного {@code paste_code}:
     * {@code (handler, text) → Boolean}. {@code true} — штатную вставку не выполнять.
     */
    public static Object handlePasteCode(Object handler, Object textObj)
    {
        try
        {
            String text = textObj instanceof String s ? s : null;
            if (handler == null || text == null)
                return Boolean.FALSE;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return Boolean.FALSE;
            if (display.getThread() == Thread.currentThread())
                return Boolean.valueOf(handleOnUi(handler, text));
            boolean[] handled = { false };
            display.syncExec(() -> handled[0] = handleOnUi(handler, text));
            return Boolean.valueOf(handled[0]);
        }
        catch (Throwable ignored)
        {
            return Boolean.FALSE;
        }
    }

    private static boolean handleOnUi(Object handler, String text)
    {
        try
        {
            Object ui = Global.getField(handler, "ui"); //$NON-NLS-1$
            ISourceViewer viewer = lastSourceViewer(ui);
            if (viewer == null)
                return false;
            Point range = viewer.getSelectedRange();
            int selLen = range == null ? -1 : range.y;
            if (selLen <= 0)
                return false;

            TextEditor.Context ctx = contextFromViewer(ui, viewer);
            if (ctx == null)
                return false;
            if (!ctx.editable)
            {
                ToastNotification.show(PasteWithCompareActions.MENU_LABEL,
                    "Редактор доступен только для чтения.", 4000);
                return true;
            }

            Shell shell = resolveShell(ui);
            String processed = preprocess(handler, text);
            if (PasteWithCompareActions.runWithNewText(shell, ctx, processed))
                return true;
            TextEditor.replaceSelectionAndSelect(ctx, processed);
            return true;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    private static void interceptNativeReplaceQuestion(Display display, Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(WRAPPED_KEY)))
            return;
        String message = findLabelText(shell);
        if (!NATIVE_QUESTION_RU.equals(message) && !NATIVE_QUESTION_EN.equals(message))
            return;
        shell.setData(WRAPPED_KEY, Boolean.TRUE);
        if (!shell.isDisposed())
            shell.setVisible(false);

        Object webView = lastWebView;
        Object handler = lastHandler;
        runOnFx(() ->
        {
            String pasted = readLastPaste(webView);
            display.asyncExec(() -> finishNativeQuestion(shell, handler, pasted));
        });
    }

    private static void finishNativeQuestion(Shell shell, Object handler, String pasted)
    {
        try
        {
            if (pasted == null || pasted.isEmpty() || handler == null)
            {
                if (shell != null && !shell.isDisposed())
                    shell.setVisible(true);
                return;
            }
            cancelQuestionDialog(shell);
            handleOnUi(handler, pasted);
        }
        catch (Throwable ignored)
        {
            if (shell != null && !shell.isDisposed())
                shell.setVisible(true);
        }
    }

    private static String readLastPaste(Object webView)
    {
        if (webView == null)
            return ""; //$NON-NLS-1$
        try
        {
            Object engine = Global.invoke(webView, "getEngine"); //$NON-NLS-1$
            Object result = Global.invoke(engine, "executeScript", READ_PASTE_JS); //$NON-NLS-1$
            return result instanceof String s ? s : (result == null ? "" : String.valueOf(result)); //$NON-NLS-1$
        }
        catch (Throwable ignored)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private static void cancelQuestionDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        Object window = shell.getData(WINDOW_DATA_KEY);
        if (window == null)
            window = shell.getData();
        if (window != null)
        {
            Global.invokeVoid(window, "setReturnCode", Integer.valueOf(Window.CANCEL)); //$NON-NLS-1$
            Global.invokeVoid(window, "close"); //$NON-NLS-1$
        }
        if (!shell.isDisposed())
            shell.close();
    }

    private static String findLabelText(Composite composite)
    {
        for (Control child : composite.getChildren())
        {
            if (child instanceof org.eclipse.swt.widgets.Label swtLabel)
            {
                String text = swtLabel.getText();
                if (text != null && !text.isBlank())
                    return text.replace("&", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (child instanceof Composite childComposite)
            {
                String nested = findLabelText(childComposite);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static String preprocess(Object handler, String text)
    {
        Object preprocessor = Global.getField(handler, "textPreprocessor"); //$NON-NLS-1$
        Object processed = Global.invoke(preprocessor, "process", text); //$NON-NLS-1$
        return processed instanceof String s ? s : text;
    }

    private static ISourceViewer lastSourceViewer(Object ui)
    {
        Object opt = Global.invoke(ui, "getLastSourceViewer"); //$NON-NLS-1$
        Object value = unwrapOptional(opt);
        return value instanceof ISourceViewer viewer ? viewer : null;
    }

    private static TextEditor.Context contextFromViewer(Object ui, ISourceViewer viewer)
    {
        ITextEditor editor = findOwningEditor(viewer);
        if (editor != null)
        {
            TextEditor.Context ctx = TextEditor.resolveContext(editor, editor);
            if (ctx != null && ctx.viewer == viewer)
                return ctx;
        }
        boolean editable = true;
        if (viewer.getTextWidget() instanceof StyledText styledText)
            editable = styledText.getEditable();
        return TextEditor.buildContext(viewer, compareExtension(ui, viewer, editor), editable);
    }

    private static String compareExtension(Object ui, ISourceViewer viewer, ITextEditor editor)
    {
        if (editor != null)
            return TextEditor.resolveCompareViewerType(editor);
        if (!(viewer instanceof SourceViewer sourceViewer))
            return "txt"; //$NON-NLS-1$
        Object file = unwrapOptional(Global.invoke(ui, "getFile", sourceViewer)); //$NON-NLS-1$
        if (file instanceof IFile iFile)
        {
            String ext = iFile.getFileExtension();
            if (ext != null && !ext.isBlank())
                return ext;
        }
        return "txt"; //$NON-NLS-1$
    }

    private static ITextEditor findOwningEditor(ISourceViewer viewer)
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return null;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                ITextEditor match = matchEditor(page.getActiveEditor(), viewer);
                if (match != null)
                    return match;
                for (IEditorReference ref : page.getEditorReferences())
                {
                    match = matchEditor(ref.getEditor(false), viewer);
                    if (match != null)
                        return match;
                }
            }
        }
        return null;
    }

    private static ITextEditor matchEditor(IEditorPart part, ISourceViewer viewer)
    {
        if (part == null)
            return null;
        ITextEditor editor = TextEditor.resolveTextEditor(part);
        if (editor != null && TextEditor.getSourceViewer(editor) == viewer)
            return editor;
        return null;
    }

    private static Shell resolveShell(Object ui)
    {
        Object opt = Global.invoke(ui, "getShell"); //$NON-NLS-1$
        Object value = unwrapOptional(opt);
        if (value instanceof Shell shell && !shell.isDisposed())
            return shell;
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        if (display == null || display.isDisposed())
            return null;
        Shell active = display.getActiveShell();
        if (active != null && !active.isDisposed())
            return active;
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null)
                return window.getShell();
        }
        catch (Exception ignored)
        {
            // нет workbench
        }
        return null;
    }

    private static Object unwrapOptional(Object value)
    {
        if (value instanceof Optional<?> optional)
            return optional.orElse(null);
        return value;
    }

    private static final class PasteCodeTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!TARGET_INTERNAL.equals(className))
                return null;
            try
            {
                return transformPasteCode(classfileBuffer);
            }
            catch (Throwable ignored)
            {
                return null;
            }
        }
    }

    static byte[] transformPasteCode(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!hasPasteCode(reader))
            return null;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                return "java/lang/Object"; //$NON-NLS-1$
            }
        };
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
                if (!"paste_code".equals(name) || !PASTE_DESC.equals(descriptor)) //$NON-NLS-1$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitCode()
                    {
                        emitPasteGuard(this);
                        touched.set(true);
                        super.visitCode();
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    /**
     * В начале {@code paste_code}: {@code BiFunction} из {@link #PROP_PASTE_CODE}
     * вызывается один раз; {@code true} — выход без штатной вставки.
     * Результат в local 4/5, чтобы стек в точке {@code original} был пуст.
     */
    private static void emitPasteGuard(MethodVisitor mv)
    {
        org.objectweb.asm.Label original = new org.objectweb.asm.Label();

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(PROP_PASTE_CODE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BiFunction"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, original);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Boolean"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, original);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean"); //$NON-NLS-1$
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", //$NON-NLS-1$ //$NON-NLS-2$
            "()Z", false); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, original);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(original);
    }

    private static boolean hasPasteCode(ClassReader reader)
    {
        AtomicBoolean found = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                if ("paste_code".equals(name) && PASTE_DESC.equals(descriptor)) //$NON-NLS-1$
                    found.set(true);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found.get();
    }
}
