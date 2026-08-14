package tormozit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Двойной щелчок по маркеру на линейке обзора BSL-редактора (справа от текста)
 * разворачивает сворачиваемые группы, скрывающие строки этого маркера.
 *
 * <p>Штатный {@code OverviewRuler} на {@code mouseDown} вызывает только
 * {@code ITextViewer.revealRange}. У {@code TextViewer} скрытый свёрткой диапазон
 * проецируется в заголовок группы ({@code modelRange2ClosestWidgetRange}) — переход
 * есть, разворота нет. {@code ITextViewerExtension5.exposeModelRange} как в
 * {@link TextEditorFastSearchHandler} раскрывает все пересекающиеся свёртки.
 */
public final class BslOverviewRulerUnfoldHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDoubleClick, BslOverviewRulerUnfoldHook::handleMouseDoubleClick);
    }

    private static void handleMouseDoubleClick(Event e)
    {
        if (e.button != 1 || !(e.widget instanceof Canvas canvas) || canvas.isDisposed())
            return;
        if (!isSiblingOfStyledText(canvas))
            return;

        BslXtextEditor editor = activeBslEditor();
        if (editor == null)
            return;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return;
        IOverviewRuler ruler = overviewRulerOf(viewer);
        if (ruler == null || ruler.getControl() != canvas)
            return;
        if (!ruler.hasAnnotation(e.y))
            return;

        unfoldMarkerLine(viewer, ruler, e.y);
    }

    private static void unfoldMarkerLine(ISourceViewer viewer, IOverviewRuler ruler, int y)
    {
        IDocument document = viewer.getDocument();
        if (document == null)
            return;
        IRegion range = markerRange(ruler, document, y);
        if (range == null)
            return;

        if (viewer instanceof ITextViewerExtension5 ext5)
            ext5.exposeModelRange(range);
        viewer.revealRange(range.getOffset(), range.getLength());
        viewer.setSelectedRange(range.getOffset(), 0);
        StyledText text = viewer.getTextWidget();
        if (text != null && !text.isDisposed())
            text.setFocus();
    }

    private static IRegion markerRange(IOverviewRuler ruler, IDocument document, int y)
    {
        int line = ruler.toDocumentLineNumber(y);
        if (line < 0)
            line = ruler.getLineOfLastMouseButtonActivity();
        if (line < 0)
            return null;
        try
        {
            IRegion lineInfo = document.getLineInformation(line);
            int length = Math.max(lineInfo.getLength(), 1);
            return new Region(lineInfo.getOffset(), length);
        }
        catch (BadLocationException ex)
        {
            return null;
        }
    }

    private static BslXtextEditor activeBslEditor()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        BslXtextEditor editor = GetRef.getActiveBslEditor(page.getActiveEditor());
        if (editor != null)
            return editor;
        return GetRef.getActiveBslEditor(page.getActivePart());
    }

    private static IOverviewRuler overviewRulerOf(ISourceViewer viewer)
    {
        Object ruler = Global.invoke(viewer, "getOverviewRuler"); //$NON-NLS-1$
        if (ruler instanceof IOverviewRuler overview)
            return overview;
        ruler = Global.getField(viewer, "fOverviewRuler"); //$NON-NLS-1$
        return ruler instanceof IOverviewRuler overview ? overview : null;
    }

    private static boolean isSiblingOfStyledText(Canvas canvas)
    {
        if (!(canvas.getParent() instanceof Composite parent))
            return false;
        for (Control child : parent.getChildren())
        {
            if (child instanceof StyledText)
                return true;
        }
        return false;
    }
}
