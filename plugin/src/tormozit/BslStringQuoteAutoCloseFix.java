package tormozit;

import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import org.eclipse.xtext.ui.editor.model.TerminalsTokenTypeToPartitionMapper;

/**
 * Правит частный случай {@code org.eclipse.xtext.ui.editor.autoedit.PartitionInsertEditStrategy}:
 * при вводе {@code "} внутри уже открытого многострочного BSL/QL строкового литерала (текущая
 * или следующая строка начинается с {@code |} — продолжение литерала) штатная логика всё равно
 * досчитывает чётность кавычек в пределах однострочной партиции
 * {@value TerminalsTokenTypeToPartitionMapper#STRING_LITERAL_PARTITION} так, будто это обычная
 * однострочная строка, и может лишний раз вставить закрывающую {@code "}. В отличие от
 * исправления {@code &} перед {@code (}
 * ({@code Activator.QueryParameterAutoCloseFix}), у этого движка нет подменяемого статического
 * предиката — решение целиком зашито внутри {@code internalCustomizeDocumentCommand} и объект
 * создаётся заново при каждой сборке списка автоправок редактора. Поэтому вместо предотвращения
 * ловим уже готовую вставку через {@link IDocumentListener} и сразу убираем лишний символ.
 *
 * <p>Устанавливается на редактор модуля, в т.ч. встроенный в формы ({@link BslXtextEditor}
 * внутри {@link DtGranularEditorXtextEditorPage}).
 */
public final class BslStringQuoteAutoCloseFix implements IStartup
{
    private static final Map<IDocument, IDocumentListener> attached = new WeakHashMap<>();
    private static final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners = new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                registerWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new WindowListener());
        });
    }

    private static void registerWindow(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(new PartListener());
        for (IWorkbenchPage page : window.getPages())
            for (IEditorReference ref : page.getEditorReferences())
                inspectEditor(ref);
    }

    private static void inspectEditor(IWorkbenchPartReference ref)
    {
        try
        {
            IWorkbenchPart part = ref.getPart(false);
            Global.tempLog("quoteAutoClose", "inspectEditor: part=" //$NON-NLS-1$ //$NON-NLS-2$
                + (part == null ? "null" : part.getClass().getName())); //$NON-NLS-1$
            if (part instanceof BslXtextEditor bsl)
                attachToBslEditor(bsl);
            else if (part instanceof DtGranularEditor<?> granular)
                attachToGranularEditor(granular);
        }
        catch (Exception e)
        {
            Global.tempLogException("quoteAutoClose", "inspectEditor", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void attachToGranularEditor(DtGranularEditor<?> editor)
    {
        try
        {
            attachToFormPage(editor.getActivePageInstance());
            if (!pageListeners.containsKey(editor))
            {
                IPageChangedListener listener = event -> attachToFormPage(event.getSelectedPage());
                editor.addPageChangedListener(listener);
                pageListeners.put(editor, listener);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static void attachToFormPage(Object page)
    {
        if (page instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
        {
            IEditorPart embedded = xtextPage.getEmbeddedEditor();
            if (embedded instanceof BslXtextEditor bsl)
                attachToBslEditor(bsl);
        }
    }

    private static void attachToBslEditor(BslXtextEditor editor)
    {
        try
        {
            Global.tempLog("quoteAutoClose", "attachToBslEditor: " + editor); //$NON-NLS-1$ //$NON-NLS-2$
            attach(editor.getInternalSourceViewer());
        }
        catch (Exception e)
        {
            Global.tempLogException("quoteAutoClose", "attachToBslEditor", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void attach(ISourceViewer viewer)
    {
        if (viewer == null)
        {
            Global.tempLog("quoteAutoClose", "attach: viewer=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IDocument document = viewer.getDocument();
        if (document == null)
        {
            Global.tempLog("quoteAutoClose", "attach: document=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (attached.containsKey(document))
        {
            Global.tempLog("quoteAutoClose", "attach: already attached doc=" + System.identityHashCode(document)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IDocumentListener listener = new QuoteFixListener();
        document.addDocumentListener(listener);
        attached.put(document, listener);
        Global.tempLog("quoteAutoClose", "attach: OK doc=" + System.identityHashCode(document)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class PartListener implements IPartListener2
    {
        @Override
        public void partOpened(IWorkbenchPartReference ref)
        {
            inspectEditor(ref);
        }
    }

    private static final class WindowListener implements IWindowListener
    {
        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            registerWindow(window);
        }

        @Override
        public void windowClosed(IWorkbenchWindow window)
        {
        }

        @Override
        public void windowActivated(IWorkbenchWindow window)
        {
        }

        @Override
        public void windowDeactivated(IWorkbenchWindow window)
        {
        }
    }

    private static final class QuoteFixListener implements IDocumentListener
    {
        @Override
        public void documentAboutToBeChanged(DocumentEvent event)
        {
        }

        @Override
        public void documentChanged(DocumentEvent event)
        {
            String text = event.getText();
            if (event.getLength() != 0 || text == null || !text.equals("\"\"")) //$NON-NLS-1$
            {
                if (text != null && text.indexOf('"') >= 0)
                    Global.tempLog("quoteAutoClose", "documentChanged: skip len=" + event.getLength() //$NON-NLS-1$ //$NON-NLS-2$
                        + " text=" + escape(text)); //$NON-NLS-1$
                return;
            }
            IDocument document = event.getDocument();
            int offset = event.getOffset();
            try
            {
                boolean inside = insideMultilineStringLiteral(document, offset);
                Global.tempLog("quoteAutoClose", "documentChanged: offset=" + offset //$NON-NLS-1$ //$NON-NLS-2$
                    + " inside=" + inside); //$NON-NLS-1$
                if (!inside)
                    return;
                document.replace(offset + 1, 1, ""); //$NON-NLS-1$
                Global.tempLog("quoteAutoClose", "documentChanged: corrected offset=" + offset); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (BadLocationException e)
            {
                Global.tempLogException("quoteAutoClose", "documentChanged", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private static String escape(String s)
        {
            return s.replace("\r", "\\r").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        /**
         * {@code offset} — начало только что вставленной пары кавычек; символ перед ним
         * (позиция не затронута вставкой) сохраняет свою исходную классификацию партиции.
         *
         * <p>Партиция {@code __string} у BSL/QL однострочная даже внутри многострочного
         * литерала — построчный сканер партиций не тянет её через перевод строки
         * (подтверждено логом: {@code partitionOffset}/{@code partitionLen} укладываются
         * в одну {@code currentLine}). Поэтому «многострочность» определяем по тексту
         * строк, а не по границам партиции: продолжение BSL/QL-литерала помечается
         * ведущим {@code |} на следующей строке (или на самой текущей, если каретка уже
         * внутри такой строки-продолжения).
         */
        private boolean insideMultilineStringLiteral(IDocument document, int offset) throws BadLocationException
        {
            if (offset <= 0)
            {
                Global.tempLog("quoteAutoClose", "insideMultilineStringLiteral: offset<=0"); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
            ITypedRegion partition = document.getPartition(offset - 1);
            Global.tempLog("quoteAutoClose", "insideMultilineStringLiteral: partitionType=" + partition.getType() //$NON-NLS-1$ //$NON-NLS-2$
                + " partitionOffset=" + partition.getOffset() + " partitionLen=" + partition.getLength()); //$NON-NLS-1$ //$NON-NLS-2$
            if (!TerminalsTokenTypeToPartitionMapper.STRING_LITERAL_PARTITION.equals(partition.getType()))
                return false;
            int currentLine = document.getLineOfOffset(offset);
            boolean currentStartsWithPipe = startsWithPipe(document, currentLine);
            boolean nextStartsWithPipe = currentLine + 1 < document.getNumberOfLines()
                && startsWithPipe(document, currentLine + 1);
            Global.tempLog("quoteAutoClose", "insideMultilineStringLiteral: currentLine=" + currentLine //$NON-NLS-1$ //$NON-NLS-2$
                + " currentStartsWithPipe=" + currentStartsWithPipe //$NON-NLS-1$
                + " nextStartsWithPipe=" + nextStartsWithPipe); //$NON-NLS-1$
            return currentStartsWithPipe || nextStartsWithPipe;
        }

        private boolean startsWithPipe(IDocument document, int line) throws BadLocationException
        {
            IRegion info = document.getLineInformation(line);
            String lineText = document.get(info.getOffset(), info.getLength());
            int i = 0;
            while (i < lineText.length() && Character.isWhitespace(lineText.charAt(i)))
                i++;
            return i < lineText.length() && lineText.charAt(i) == '|';
        }
    }
}
