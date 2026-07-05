package tormozit;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Поиск активного текстового редактора (в т.ч. вложенного в {@link DtGranularEditor})
 * и замена выделенного фрагмента.
 */
public final class TextEditor
{
    /** Расширение файла для выбора merge-viewer Eclipse Compare (см. {@code extensions} в plugin.xml EDT). */
    private static final String FALLBACK_COMPARE_EXTENSION = "txt"; //$NON-NLS-1$
    private static final String BSL_COMPARE_EXTENSION = "bsl"; //$NON-NLS-1$
    /** Расширение языка запросов EDT ({@code com._1c.g5.v8.dt.ql}). */
    static final String QL_COMPARE_EXTENSION = "ql"; //$NON-NLS-1$

    private TextEditor()
    {
    }

    /** Контекст активного текстового редактора. */
    public static final class Context
    {
        public final ITextEditor editor;
        public final ISourceViewer viewer;
        public final int offset;
        public final int length;
        public final String selectedText;
        /** Расширение для {@link org.eclipse.compare.ITypedElement#getType()} (например {@code bsl}). */
        public final String compareViewerType;
        public final boolean editable;

        Context(ITextEditor editor, ISourceViewer viewer, int offset, int length,
            String selectedText, String compareViewerType, boolean editable)
        {
            this.editor = editor;
            this.viewer = viewer;
            this.offset = offset;
            this.length = length;
            this.selectedText = selectedText;
            this.compareViewerType = compareViewerType;
            this.editable = editable;
        }
    }

    /**
     * Разрешает {@link ITextEditor} из активной части и/или редактора Workbench.
     */
    public static Context resolveContext(IWorkbenchPart part, IEditorPart editorPart)
    {
        ITextEditor textEditor = resolveTextEditor(part);
        if (textEditor == null && editorPart != null)
            textEditor = resolveTextEditor(editorPart);
        if (textEditor == null)
            return null;
        return buildContext(textEditor);
    }

    /**
     * Контекст текстового редактора по фокусу клавиатуры (модули, вложенные viewer,
     * модальный «Редактор запроса» и т.п.).
     */
    public static Context resolveContextFromFocus()
    {
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        if (display == null || display.isDisposed())
            return null;
        return resolveContextFromFocus(display.getFocusControl());
    }

    public static Context resolveContextFromFocus(Control focus)
    {
        if (focus == null || focus.isDisposed())
            return null;

        Context queryCtx = QueryTextEditDialogHook.tryBuildPasteContext(focus);
        if (queryCtx != null)
            return queryCtx;

        Context workbenchCtx = resolveWorkbenchContextFromFocus(focus);
        if (workbenchCtx != null)
            return workbenchCtx;

        ISourceViewer viewer = resolveViewerFromFocus(focus);
        if (viewer == null)
            return null;

        boolean editable = true;
        Control textWidget = viewer.getTextWidget();
        if (textWidget instanceof StyledText styledText)
            editable = styledText.getEditable();

        return buildContext(viewer, FALLBACK_COMPARE_EXTENSION, editable);
    }

    private static Context resolveWorkbenchContextFromFocus(Control focus)
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IEditorPart activeEditor = page.getActiveEditor();
            if (activeEditor == null)
                return null;

            ITextEditor textEditor = resolveTextEditor(activeEditor);
            if (textEditor == null)
                return null;
            if (!focusInEditorViewer(focus, textEditor))
                return null;
            return buildContext(textEditor);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static boolean focusInEditorViewer(Control focus, ITextEditor editor)
    {
        ISourceViewer viewer = getSourceViewer(editor);
        if (viewer == null)
            return false;
        Control textWidget = viewer.getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return false;
        for (Control c = focus; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c == textWidget)
                return true;
        }
        return false;
    }

    public static ISourceViewer resolveViewerFromFocus()
    {
        Display display = Display.getCurrent();
        if (display == null) return null;
        Control focus = display.getFocusControl();
        if (focus == null || focus.isDisposed()) return null;
        return resolveViewerFromFocus(focus);
    }

    public static ISourceViewer resolveViewerFromFocus(Control focus)
    {
        for (Control c = focus; c != null && !c.isDisposed(); c = c.getParent())
        {
            ISourceViewer adapted = Adapters.adapt(c, ISourceViewer.class);
            if (adapted != null)
                return adapted;

            for (String method : new String[] {
                "getViewer", "getSourceViewer", "getTextViewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Object viewerObj = Global.invoke(c, method);
                if (viewerObj instanceof ISourceViewer sourceViewer)
                    return sourceViewer;
            }
        }
        return null;
    }

    /**
     * Контекст для встроенного {@link ISourceViewer} без {@link ITextEditor}
     * (например «Редактор запроса»).
     */
    public static Context buildContext(
        ISourceViewer viewer, String compareViewerType, boolean editable)
    {
        if (viewer == null)
            return null;

        IDocument doc = viewer.getDocument();
        if (doc == null)
            return null;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel))
            return null;

        int offset = textSel.getOffset();
        int length = textSel.getLength();
        String selected = textSel.getText();
        if (selected == null)
            selected = ""; //$NON-NLS-1$

        String ext = compareViewerType != null && !compareViewerType.isBlank()
            ? compareViewerType
            : FALLBACK_COMPARE_EXTENSION;

        return new Context(
            null,
            viewer,
            offset,
            length,
            selected,
            ext,
            editable);
    }

    public static ITextEditor resolveTextEditor(IWorkbenchPart part)
    {
        if (part instanceof ITextEditor textEditor)
            return textEditor;
        if (part instanceof DtGranularEditor<?> granular)
            return embeddedTextEditor(granular);
        return null;
    }

    private static ITextEditor embeddedTextEditor(DtGranularEditor<?> granular)
    {
        IFormPage activePage = granular.getActivePageInstance();
        if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
        {
            IEditorPart embedded = xtextPage.getEmbeddedEditor();
            if (embedded instanceof ITextEditor textEditor)
                return textEditor;
        }
        return null;
    }

    private static Context buildContext(ITextEditor editor)
    {
        ISourceViewer viewer = getSourceViewer(editor);
        if (viewer == null)
            return null;

        IDocument doc = viewer.getDocument();
        if (doc == null)
            return null;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel))
            return null;

        int offset = textSel.getOffset();
        int length = textSel.getLength();
        String selected = textSel.getText();
        if (selected == null)
            selected = ""; //$NON-NLS-1$

        return new Context(
            editor,
            viewer,
            offset,
            length,
            selected,
            resolveCompareViewerType(editor),
            editor.isEditable());
    }

    public static ISourceViewer getSourceViewer(ITextEditor editor)
    {
        if (editor instanceof BslXtextEditor bslEditor)
            return bslEditor.getInternalSourceViewer();

        ISourceViewer adapted = editor.getAdapter(ISourceViewer.class);
        if (adapted != null)
            return adapted;

        Object viewer = Global.invoke(editor, "getSourceViewer"); //$NON-NLS-1$
        if (viewer instanceof ISourceViewer sourceViewer)
            return sourceViewer;

        return null;
    }

    /**
     * Тип элемента сравнения для Eclipse Compare — <b>расширение файла</b>, не content type ID.
     * Для BSL EDT регистрирует merge-viewer с {@code extensions="bsl"}.
     */
    static String resolveCompareViewerType(ITextEditor editor)
    {
        try
        {
            IEditorInput input = editor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                {
                    String ext = file.getFileExtension();
                    if (ext != null && !ext.isBlank())
                        return ext;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("TextEditor.resolveCompareViewerType: " + e); //$NON-NLS-1$
        }

        if (editor instanceof BslXtextEditor)
            return BSL_COMPARE_EXTENSION;

        return FALLBACK_COMPARE_EXTENSION;
    }

    /**
     * Заменяет выделенный фрагмент и выделяет вставленный диапазон.
     */
    public static void replaceSelectionAndSelect(Context ctx, String newText)
    {
        if (ctx == null || newText == null)
            return;

        IDocument doc = ctx.viewer.getDocument();
        if (doc == null)
            return;

        if (doc instanceof IXtextDocument xtextDoc)
        {
            xtextDoc.modify(resource ->
            {
                try
                {
                    xtextDoc.replace(ctx.offset, ctx.length, newText);
                }
                catch (BadLocationException e)
                {
                    throw new RuntimeException("Ошибка замены текста", e); //$NON-NLS-1$
                }
                return null;
            });
        }
        else
        {
            try
            {
                doc.replace(ctx.offset, ctx.length, newText);
            }
            catch (BadLocationException e)
            {
                Global.log("TextEditor.replaceSelectionAndSelect: " + e); //$NON-NLS-1$
                return;
            }
        }

        int newLength = newText.length();
        ctx.viewer.getSelectionProvider().setSelection(
            new org.eclipse.jface.text.TextSelection(ctx.offset, newLength));
        ctx.viewer.setSelectedRange(ctx.offset, newLength);
    }

    public static String readClipboardText(Shell shell)
    {
        Display display = shell != null ? shell.getDisplay() : Display.getDefault();
        if (display == null || display.isDisposed())
            return null;
        Clipboard cb = new Clipboard(display);
        try
        {
            return (String) cb.getContents(TextTransfer.getInstance());
        }
        finally
        {
            cb.dispose();
        }
    }

    /**
     * Порт RDT {@code СохранитьГраницыВыделенияДляОтмены}: identity {@code doc.replace} + {@code commit()}
     * регистрирует границы выделения в undo-стеке EDT. UI-поток, отдельно от {@code doc.modify}.
     *
     * @return смещение длины текста (аналог {@code СмещениеНачала} в RDT)
     */
    static int saveSelectionBoundsForUndo(ISourceViewer viewer)
    {
        if (viewer == null)
            return 0;

        IDocument doc = viewer.getDocument();
        if (doc == null)
            return 0;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel))
            return 0;

        int offset = textSel.getOffset();
        int length = textSel.getLength();
        if (length <= 0)
            return 0;

        IDocumentUndoManager undoManager = DocumentUndoManagerRegistry.getDocumentUndoManager(doc);
        if (undoManager == null)
            return 0;

        long started = System.currentTimeMillis();
        try
        {
            int lenBefore = doc.get().length();
            String selected = doc.get(offset, length);
            doc.replace(offset, length, selected);
            undoManager.commit();
            int offsetAdjust = doc.get().length() - lenBefore;
            IRModuleSyncDebug.logSaveSelectionForUndo(
                offset, length, offsetAdjust, System.currentTimeMillis() - started);
            return offsetAdjust;
        }
        catch (BadLocationException e)
        {
            IRModuleSyncDebug.problem("saveSelectionForUndo: " + e.getMessage()); //$NON-NLS-1$
            return 0;
        }
    }

    public static boolean clipboardHasText(Shell shell)
    {
        String text = readClipboardText(shell);
        return text != null && !text.isEmpty();
    }

    /**
     * Активирует workbench и переводит фокус в BSL-редактор (в т.ч. вложенный в {@link DtGranularEditor}).
     */
    public static void focusBslEditor(BslXtextEditor editor)
    {
        if (editor == null)
            return;

        Runnable focus = () ->
        {
            if (WinWindowActivator.isWindows())
                WinWindowActivator.activateWorkbench();

            try
            {
                IWorkbenchPage page = editor.getSite().getPage();
                if (page != null)
                {
                    IEditorPart toActivate = editor;
                    IWorkbenchPart sitePart = editor.getSite().getPart();
                    if (sitePart instanceof IEditorPart parent)
                        toActivate = parent;
                    page.activate(toActivate);
                }
            }
            catch (Exception ignored)
            {
            }

            editor.setFocus();
            ISourceViewer viewer = editor.getInternalSourceViewer();
            if (viewer != null)
            {
                StyledText widget = viewer.getTextWidget();
                if (widget != null && !widget.isDisposed())
                    widget.setFocus();
            }
        };

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (Display.getCurrent() == display)
            focus.run();
        else
            display.syncExec(focus);
    }

    /** Активирует workbench и переводит фокус в {@link ISourceViewer} (редактор запроса и т.п.). */
    public static void focusSourceViewer(ISourceViewer viewer)
    {
        if (viewer == null)
            return;

        Runnable focus = () ->
        {
            if (WinWindowActivator.isWindows())
                WinWindowActivator.activateWorkbench();

            StyledText widget = viewer.getTextWidget();
            if (widget != null && !widget.isDisposed())
                widget.setFocus();
        };

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (Display.getCurrent() == display)
            focus.run();
        else
            display.syncExec(focus);
    }
}
