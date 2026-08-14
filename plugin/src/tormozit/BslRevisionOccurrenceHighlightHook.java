package tormozit;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IPainter;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * В BSL-редакторе без {@link IFileEditorInput} (модуль из коммита Git —
 * {@code FileRevisionEditorInput}) штатный документ-провайдер не создаёт
 * annotation model, и подсветка текущего идентификатора не рисуется.
 * Подставляет in-memory {@link AnnotationModel}, чтобы штатный
 * {@code BslOccurrenceMarker} писал вхождения как в редакторе модуля проекта.
 */
public final class BslRevisionOccurrenceHighlightHook implements IStartup
{
    private static final String INSTALLED_MARKER = "tormozit.bslRevisionOccurrenceModel"; //$NON-NLS-1$
    private static final int MAX_ATTACH_ATTEMPTS = 100;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed instanceof BslXtextEditor bsl)
                    hookBslEditor(bsl);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookFromPartRef(ref);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                hookFromPartRef(ref);
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partVisible(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private void hookFromPartRef(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference))
            return;
        IEditorPart ed = ((IEditorReference) ref).getEditor(false);
        if (ed instanceof BslXtextEditor bsl)
            hookBslEditor(bsl);
    }

    private void hookBslEditor(BslXtextEditor editor)
    {
        Display.getDefault().asyncExec(() -> attachToBslEditor(editor, 0));
    }

    private void attachToBslEditor(BslXtextEditor editor, int attempt)
    {
        if (editor.getSite() == null || isWorkbenchClosing())
            return;

        IEditorInput input = editor.getEditorInput();
        if (input instanceof IFileEditorInput)
            return;

        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof SourceViewer sourceViewer))
        {
            if (attempt >= MAX_ATTACH_ATTEMPTS)
                return;
            Display.getDefault().asyncExec(() -> attachToBslEditor(editor, attempt + 1));
            return;
        }

        StyledText textWidget = sourceViewer.getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;
        if (Boolean.TRUE.equals(textWidget.getData(INSTALLED_MARKER)))
            return;

        if (sourceViewer.getAnnotationModel() != null)
            return;

        IDocument master = editor.getDocument();
        if (master == null)
            return;

        Point selectedRange = sourceViewer.getSelectedRange();
        int topIndex = textWidget.getTopIndex();
        try
        {
            sourceViewer.setDocument(master, new AnnotationModel());
        }
        catch (RuntimeException e)
        {
            return;
        }
        try
        {
            if (sourceViewer instanceof ProjectionViewer projection && !projection.isProjectionMode())
                projection.enableProjection();
        }
        catch (RuntimeException ignored)
        {
        }
        if (textWidget.isDisposed())
            return;
        textWidget.setData(INSTALLED_MARKER, Boolean.TRUE);
        if (selectedRange != null)
            sourceViewer.setSelectedRange(selectedRange.x, selectedRange.y);
        textWidget.setTopIndex(topIndex);

        activateOccurrencePainter(editor, sourceViewer);
    }

    /**
     * Painter мог быть создан при {@code getAnnotationModel() == null} и так и не
     * подписаться на модель. После подстановки модели активируем его штатным
     * {@code paint(CONFIGURATION)}.
     */
    private static void activateOccurrencePainter(BslXtextEditor editor, SourceViewer viewer)
    {
        Object support = Global.getField(editor, "fSourceViewerDecorationSupport"); //$NON-NLS-1$
        if (support != null)
            Global.invoke(support, "updateTextDecorations"); //$NON-NLS-1$

        Object painter = support != null
            ? Global.getField(support, "fAnnotationPainter") //$NON-NLS-1$
            : null;
        if (painter == null)
            painter = Global.getField(viewer, "fAnnotationPainter"); //$NON-NLS-1$
        if (painter != null)
            Global.invoke(painter, "paint", Integer.valueOf(IPainter.CONFIGURATION)); //$NON-NLS-1$
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }
}
