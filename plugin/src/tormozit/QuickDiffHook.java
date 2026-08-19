package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.IAnnotationModelListener;
import org.eclipse.jface.text.source.IChangeRulerColumn;
import org.eclipse.jface.text.source.ILineDiffer;
import org.eclipse.jface.text.source.ILineDifferExtension;
import org.eclipse.jface.text.source.ILineDifferExtension2;
import org.eclipse.jface.text.source.ILineDiffInfo;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.LineNumberChangeRulerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Скрывает маркеры Quick Diff («Выделение изменений»), если в разнице только
 * непечатные символы: полоса номеров слева и линейка обзора справа.
 *
 * <p>На полосе номеров {@code DiffPainter} получает обёртку {@link ILineDiffer}
 * и не рисует пробельные добавления/изменения/удаления. На линейке обзора
 * аннотации Quick Diff с той же пробельной разницей не отдаются в отрисовку
 * (на Windows линейка рисует через {@code new GC}, минуя {@code PaintListener}).
 * Содержательные маркеры не трогаем.
 */
public final class QuickDiffHook implements IStartup
{
    private static final String LINE_NUMBER_KEY = "tormozit.quickDiffWs.lineNumber"; //$NON-NLS-1$
    private static final int MAX_ATTACH_ATTEMPTS = 100;
    private static final String QD_DELETION = "org.eclipse.ui.workbench.texteditor.quickdiffDeletion"; //$NON-NLS-1$
    private static final String QD_CHANGE = "org.eclipse.ui.workbench.texteditor.quickdiffChange"; //$NON-NLS-1$
    private static final String QD_ADDITION = "org.eclipse.ui.workbench.texteditor.quickdiffAddition"; //$NON-NLS-1$

    private static final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Paint, QuickDiffHook::beforeLineNumberPaint);
        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w) {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w) {}
        });
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);
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
                hookEditorIfNeeded(active);
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null && ed != active)
                    hookEditorIfNeeded(ed);
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

    private static void hookFromPartRef(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference editorRef))
            return;
        IEditorPart ed = editorRef.getEditor(false);
        if (ed != null)
            hookEditorIfNeeded(ed);
    }

    private static void hookEditorIfNeeded(IEditorPart editor)
    {
        ITextEditor textEditor = TextEditor.resolveTextEditor(editor);
        if (textEditor != null)
            tryAttach(textEditor, 0);
        if (editor instanceof DtGranularEditor<?> granular)
            hookGranularEditor(granular);
    }

    private static void hookGranularEditor(DtGranularEditor<?> editor)
    {
        IFormPage activePage = editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
        {
            IEditorPart embedded = xtextPage.getEmbeddedEditor();
            if (embedded instanceof ITextEditor textEditor)
                tryAttach(textEditor, 0);
        }
        if (!hookedGranularEditors.add(editor))
            return;
        editor.addPageChangedListener(new IPageChangedListener()
        {
            @Override
            public void pageChanged(PageChangedEvent event)
            {
                Object selected = event.getSelectedPage();
                if (selected instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
                {
                    IEditorPart embedded = xtextPage.getEmbeddedEditor();
                    if (embedded instanceof ITextEditor textEditor)
                        tryAttach(textEditor, 0);
                }
            }
        });
    }

    private static void tryAttach(ITextEditor editor, int attempt)
    {
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        ISourceViewer viewer = TextEditor.getSourceViewer(editor);
        StyledText text = viewer != null ? viewer.getTextWidget() : null;
        LineNumberChangeRulerColumn column = findLineNumberColumn(viewer);
        IOverviewRuler overview = overviewRulerOf(viewer);
        Control lineControl = column != null ? column.getControl() : null;
        Control overviewControl = overview != null ? overview.getControl() : null;
        boolean textReady = text != null && !text.isDisposed();
        boolean lineReady = lineControl != null && !lineControl.isDisposed();
        boolean overviewReady = overviewControl != null && !overviewControl.isDisposed();
        if (!textReady || !lineReady && !overviewReady)
        {
            if (attempt < MAX_ATTACH_ATTEMPTS && display != null && !display.isDisposed())
            {
                display.asyncExec(() -> tryAttach(editor, attempt + 1));
            }
            return;
        }
        Session session = new Session(viewer, column, overview);
        if (lineReady)
        {
            lineControl.setData(LINE_NUMBER_KEY, session);
            session.ensureWrapped();
        }
        if (overviewReady)
            session.ensureOverviewFiltered();
        if ((!lineReady || !overviewReady)
            && attempt < MAX_ATTACH_ATTEMPTS && display != null && !display.isDisposed())
        {
            display.asyncExec(() -> tryAttach(editor, attempt + 1));
        }
    }

    private static void beforeLineNumberPaint(Event e)
    {
        if (!(e.widget instanceof Control control) || control.isDisposed())
            return;
        if (control.getData(LINE_NUMBER_KEY) instanceof Session session)
            session.ensureWrapped();
    }

    private static LineNumberChangeRulerColumn findLineNumberColumn(ISourceViewer viewer)
    {
        IVerticalRuler ruler = verticalRulerOf(viewer);
        if (!(ruler instanceof CompositeRuler composite))
            return null;
        Iterator<?> columns = composite.getDecoratorIterator();
        while (columns.hasNext())
        {
            LineNumberChangeRulerColumn found = asChangeColumn(columns.next());
            if (found != null)
                return found;
        }
        return null;
    }

    /**
     * В EDT колонка номеров — {@code LineNumberColumn}: оболочка, внутри
     * {@code fDelegate} = {@link LineNumberChangeRulerColumn}.
     */
    private static LineNumberChangeRulerColumn asChangeColumn(Object column)
    {
        if (column instanceof LineNumberChangeRulerColumn changeColumn)
            return changeColumn;
        Object delegate = Global.getField(column, "fDelegate"); //$NON-NLS-1$
        return delegate instanceof LineNumberChangeRulerColumn changeColumn ? changeColumn : null;
    }

    private static IVerticalRuler verticalRulerOf(ISourceViewer viewer)
    {
        Object ruler = Global.invoke(viewer, "getVerticalRuler"); //$NON-NLS-1$
        if (ruler instanceof IVerticalRuler vertical)
            return vertical;
        ruler = Global.getField(viewer, "fVerticalRuler"); //$NON-NLS-1$
        return ruler instanceof IVerticalRuler vertical ? vertical : null;
    }

    private static IOverviewRuler overviewRulerOf(ISourceViewer viewer)
    {
        Object ruler = Global.invoke(viewer, "getOverviewRuler"); //$NON-NLS-1$
        if (ruler instanceof IOverviewRuler overview)
            return overview;
        ruler = Global.getField(viewer, "fOverviewRuler"); //$NON-NLS-1$
        return ruler instanceof IOverviewRuler overview ? overview : null;
    }

    static ILineDiffer lineDifferOf(ISourceViewer viewer)
    {
        if (viewer == null)
            return null;
        IAnnotationModel model = viewer.getAnnotationModel();
        if (model instanceof IAnnotationModelExtension ext)
        {
            IAnnotationModel diff = ext.getAnnotationModel(IChangeRulerColumn.QUICK_DIFF_MODEL_ID);
            if (diff instanceof ILineDiffer differ)
                return unwrap(differ);
        }
        return model instanceof ILineDiffer differ ? unwrap(differ) : null;
    }

    static ILineDiffer unwrap(ILineDiffer differ)
    {
        while (differ instanceof DifferWrapper wrapper)
            differ = wrapper.delegate;
        return differ;
    }

    static boolean isNonPrintable(int codePoint)
    {
        return Character.isWhitespace(codePoint) || Character.isISOControl(codePoint);
    }

    static String printable(String text)
    {
        if (text == null || text.isEmpty())
            return ""; //$NON-NLS-1$
        StringBuilder builder = new StringBuilder(text.length());
        text.codePoints().filter(cp -> !isNonPrintable(cp)).forEach(builder::appendCodePoint);
        return builder.toString();
    }

    static String lineText(IDocument document, int modelLine)
    {
        if (document == null || modelLine < 0)
            return ""; //$NON-NLS-1$
        try
        {
            return document.get(document.getLineOffset(modelLine), document.getLineLength(modelLine));
        }
        catch (BadLocationException ex)
        {
            return ""; //$NON-NLS-1$
        }
    }

    static boolean isWhitespaceOnlyChange(IDocument document, int modelLine, ILineDiffInfo info)
    {
        if (info == null)
            return false;
        int type = info.getChangeType();
        if (type != ILineDiffInfo.ADDED && type != ILineDiffInfo.CHANGED)
            return false;
        String current = lineText(document, modelLine);
        if (type == ILineDiffInfo.ADDED)
            return printable(current).isEmpty();
        String[] original = info.getOriginalText();
        if (original == null || original.length == 0)
            return false;
        return printable(original[0]).equals(printable(current));
    }

    static boolean isDeletedLinesWhitespace(ILineDiffInfo info)
    {
        if (info == null || info.getRemovedLinesBelow() <= 0)
            return false;
        String[] original = info.getOriginalText();
        if (original == null || original.length == 0)
            return false;
        int skip = 0;
        int type = info.getChangeType();
        if (type == ILineDiffInfo.ADDED || type == ILineDiffInfo.CHANGED)
            skip = 1;
        boolean sawDeleted = false;
        for (int i = skip; i < original.length; i++)
        {
            sawDeleted = true;
            if (!printable(original[i]).isEmpty())
                return false;
        }
        return sawDeleted;
    }

    static boolean isWhitespaceOnlyDeletionAbove(ILineDiffer differ, int modelLine, ILineDiffInfo info)
    {
        if (info == null || info.getRemovedLinesAbove() <= 0 || differ == null || modelLine <= 0)
            return false;
        return isDeletedLinesWhitespace(differ.getLineInfo(modelLine - 1));
    }

    static boolean hideLine(IDocument document, ILineDiffer differ, int line, ILineDiffInfo info)
    {
        boolean changeWs = isWhitespaceOnlyChange(document, line, info);
        boolean delBelow = isDeletedLinesWhitespace(info);
        boolean delAbove = isWhitespaceOnlyDeletionAbove(differ, line, info);
        return changeWs || delBelow || delAbove;
    }

    static boolean isWhitespaceQuickDiffAnnotation(Annotation annotation, ISourceViewer viewer, IAnnotationModel model)
    {
        String type = annotation.getType();
        if (type == null)
            return false;
        if (!QD_DELETION.equals(type) && !QD_CHANGE.equals(type) && !QD_ADDITION.equals(type))
            return false;
        if (!(annotation instanceof ILineDiffInfo info))
            return false;
        if (QD_DELETION.equals(type))
        {
            String[] original = info.getOriginalText();
            if (original == null || original.length == 0)
                return false;
            for (String line : original)
            {
                if (!printable(line).isEmpty())
                    return false;
            }
            return true;
        }
        IDocument document = viewer == null ? null : viewer.getDocument();
        ILineDiffer differ = lineDifferOf(viewer);
        if (document == null || differ == null)
            return false;
        Position position = model.getPosition(annotation);
        if (position == null)
            return false;
        try
        {
            int start = document.getLineOfOffset(position.getOffset());
            int lastOffset = position.getOffset() + Math.max(0, position.getLength() - 1);
            int end = document.getLineOfOffset(Math.min(lastOffset, document.getLength()));
            boolean anyHide = false;
            for (int line = start; line <= end; line++)
            {
                ILineDiffInfo lineInfo = differ.getLineInfo(line);
                if (lineInfo == null || !lineInfo.hasChanges())
                    continue;
                int changeType = lineInfo.getChangeType();
                boolean printableChange = (changeType == ILineDiffInfo.ADDED || changeType == ILineDiffInfo.CHANGED)
                    && !isWhitespaceOnlyChange(document, line, lineInfo);
                if (printableChange)
                    return false;
                if (hideLine(document, differ, line, lineInfo))
                    anyHide = true;
            }
            return anyHide;
        }
        catch (BadLocationException ex)
        {
            return false;
        }
    }

    private static final class Session
    {
        final ISourceViewer viewer;
        final LineNumberChangeRulerColumn column;
        final IOverviewRuler overview;

        Session(ISourceViewer viewer, LineNumberChangeRulerColumn column, IOverviewRuler overview)
        {
            this.viewer = viewer;
            this.column = column;
            this.overview = overview;
        }

        void ensureWrapped()
        {
            wrapPainter();
            ensureOverviewFiltered();
        }

        void wrapPainter()
        {
            if (column == null || column.isShowingRevisionInformation())
                return;
            Object painter = Global.getField(column, "fDiffPainter"); //$NON-NLS-1$
            if (painter == null)
                return;
            Object current = Global.getField(painter, "fLineDiffer"); //$NON-NLS-1$
            if (current instanceof DifferWrapper)
                return;
            if (!(current instanceof ILineDiffer differ))
                return;
            Global.setField(painter, "fLineDiffer", new DifferWrapper(differ, viewer)); //$NON-NLS-1$
        }

        void ensureOverviewFiltered()
        {
            if (overview == null)
                return;
            Object current = Global.getField(overview, "fModel"); //$NON-NLS-1$
            if (current instanceof OverviewFilterModel)
                return;
            if (!(current instanceof IAnnotationModel real))
                return;
            Global.setField(overview, "fModel", new OverviewFilterModel(real, viewer)); //$NON-NLS-1$
        }
    }

    /**
     * На Windows {@code OverviewRuler.redraw} рисует через {@code new GC}, без
     * {@code PaintListener}. Фильтр на {@code fModel} убирает пробельные Quick Diff
     * до штатной отрисовки.
     */
    private static final class OverviewFilterModel implements IAnnotationModel
    {
        final IAnnotationModel delegate;
        final ISourceViewer viewer;

        OverviewFilterModel(IAnnotationModel delegate, ISourceViewer viewer)
        {
            this.delegate = delegate;
            this.viewer = viewer;
        }

        @Override
        public Iterator<Annotation> getAnnotationIterator()
        {
            List<Annotation> kept = new ArrayList<>();
            Iterator<Annotation> iterator = delegate.getAnnotationIterator();
            while (iterator.hasNext())
            {
                Annotation annotation = iterator.next();
                if (isWhitespaceQuickDiffAnnotation(annotation, viewer, delegate))
                    continue;
                kept.add(annotation);
            }
            return kept.iterator();
        }

        @Override
        public Position getPosition(Annotation annotation)
        {
            return delegate.getPosition(annotation);
        }

        @Override
        public void addAnnotationModelListener(IAnnotationModelListener listener)
        {
            delegate.addAnnotationModelListener(listener);
        }

        @Override
        public void removeAnnotationModelListener(IAnnotationModelListener listener)
        {
            delegate.removeAnnotationModelListener(listener);
        }

        @Override
        public void addAnnotation(Annotation annotation, Position position)
        {
            delegate.addAnnotation(annotation, position);
        }

        @Override
        public void removeAnnotation(Annotation annotation)
        {
            delegate.removeAnnotation(annotation);
        }

        @Override
        public void connect(IDocument document)
        {
            delegate.connect(document);
        }

        @Override
        public void disconnect(IDocument document)
        {
            delegate.disconnect(document);
        }
    }

    private static final class DifferWrapper implements ILineDiffer, ILineDifferExtension, ILineDifferExtension2,
        IAnnotationModel
    {
        final ILineDiffer delegate;
        final ISourceViewer viewer;

        DifferWrapper(ILineDiffer delegate, ISourceViewer viewer)
        {
            this.delegate = delegate;
            this.viewer = viewer;
        }

        private IAnnotationModel model()
        {
            return delegate instanceof IAnnotationModel annotationModel ? annotationModel : null;
        }

        @Override
        public ILineDiffInfo getLineInfo(int line)
        {
            ILineDiffInfo inner = delegate.getLineInfo(line);
            if (inner == null)
                return null;
            IDocument document = viewer.getDocument();
            boolean changeWs = isWhitespaceOnlyChange(document, line, inner);
            boolean delBelow = isDeletedLinesWhitespace(inner);
            boolean delAbove = isWhitespaceOnlyDeletionAbove(delegate, line, inner);
            if (!changeWs && !delBelow && !delAbove)
                return inner;
            return new FilteredInfo(inner, changeWs, delBelow, delAbove);
        }

        @Override
        public void revertLine(int line) throws BadLocationException
        {
            delegate.revertLine(line);
        }

        @Override
        public void revertBlock(int line) throws BadLocationException
        {
            delegate.revertBlock(line);
        }

        @Override
        public void revertSelection(int line, int length) throws BadLocationException
        {
            delegate.revertSelection(line, length);
        }

        @Override
        public int restoreAfterLine(int line) throws BadLocationException
        {
            return delegate.restoreAfterLine(line);
        }

        @Override
        public void suspend()
        {
            if (delegate instanceof ILineDifferExtension ext)
                ext.suspend();
        }

        @Override
        public void resume()
        {
            if (delegate instanceof ILineDifferExtension ext)
                ext.resume();
        }

        @Override
        public boolean isSuspended()
        {
            return delegate instanceof ILineDifferExtension2 ext2 && ext2.isSuspended();
        }

        @Override
        public void addAnnotationModelListener(IAnnotationModelListener listener)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.addAnnotationModelListener(listener);
        }

        @Override
        public void removeAnnotationModelListener(IAnnotationModelListener listener)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.removeAnnotationModelListener(listener);
        }

        @Override
        public void addAnnotation(Annotation annotation, Position position)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.addAnnotation(annotation, position);
        }

        @Override
        public void removeAnnotation(Annotation annotation)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.removeAnnotation(annotation);
        }

        @Override
        public void connect(IDocument document)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.connect(document);
        }

        @Override
        public void disconnect(IDocument document)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.disconnect(document);
        }

        @Override
        public Iterator<Annotation> getAnnotationIterator()
        {
            IAnnotationModel model = model();
            if (model == null)
                return Collections.emptyIterator();
            @SuppressWarnings("unchecked")
            Iterator<Annotation> iterator = model.getAnnotationIterator();
            return iterator;
        }

        @Override
        public Position getPosition(Annotation annotation)
        {
            IAnnotationModel model = model();
            return model == null ? null : model.getPosition(annotation);
        }
    }

    private static final class FilteredInfo implements ILineDiffInfo
    {
        private final ILineDiffInfo inner;
        private final boolean hideChange;
        private final boolean hideDelBelow;
        private final boolean hideDelAbove;

        FilteredInfo(ILineDiffInfo inner, boolean hideChange, boolean hideDelBelow, boolean hideDelAbove)
        {
            this.inner = inner;
            this.hideChange = hideChange;
            this.hideDelBelow = hideDelBelow;
            this.hideDelAbove = hideDelAbove;
        }

        @Override
        public int getChangeType()
        {
            return hideChange ? UNCHANGED : inner.getChangeType();
        }

        @Override
        public int getRemovedLinesBelow()
        {
            return hideDelBelow ? 0 : inner.getRemovedLinesBelow();
        }

        @Override
        public int getRemovedLinesAbove()
        {
            return hideDelAbove ? 0 : inner.getRemovedLinesAbove();
        }

        @Override
        public boolean hasChanges()
        {
            return getChangeType() != UNCHANGED
                || getRemovedLinesAbove() > 0
                || getRemovedLinesBelow() > 0;
        }

        @Override
        public String[] getOriginalText()
        {
            return inner.getOriginalText();
        }
    }
}
