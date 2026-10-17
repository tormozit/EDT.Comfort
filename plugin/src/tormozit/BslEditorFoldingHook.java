package tormozit;

import java.util.Locale;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.information.IInformationProviderExtension2;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationRulerColumn;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationHover;
import org.eclipse.jface.text.source.IAnnotationHoverExtension;
import org.eclipse.jface.text.source.ILineRange;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.projection.IProjectionListener;
import org.eclipse.jface.text.source.projection.IProjectionPosition;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;

/**
 * Начальное сворачивание областей ({@code #Область}) по настройке «Автоматически сворачиваемые
 * области» (issue #527) — в том же месте, где EDT решает про штатные «Сворачивать автоматически»:
 * {@code BslFoldingRegionProvider.isInitiallyCollapsed(EObject)}. Тогда область рисуется свёрнутой
 * сразу, без кадра с развёрнутым текстом. Сворачивает и при открытии модуля, и по команде
 * «Сбросить сворачиваемые группы» ({@link ResetFoldingHandler}) — оба пути идут через
 * {@code initialize()} провайдера.
 *
 * <p>Вплетение в {@code isInitiallyCollapsed} делает ранний бандл {@code tormozit.comfort.early}
 * (см. его README): класс провайдера свёрток грузится раньше, чем активируется основной бандл, и
 * {@code WeavingHook} отсюда его уже не успевает. Вплетённый код вызывает функцию, которую
 * регистрирует {@link #install()} в {@code System.getProperties()}.
 */
public final class BslEditorFoldingHook
{
    /** Ключ функции; должен совпадать с {@code PROP_INITIALLY_COLLAPSED} в {@code tormozit.early.Activator}. */
    static final String PROP_INITIALLY_COLLAPSED = "tormozit.bslFolding.initiallyCollapsed"; //$NON-NLS-1$
    private static final String PROP_FOLDING_UPDATE = "tormozit.bslFolding.update"; //$NON-NLS-1$
    private static final java.util.Map<Object, FoldingUpdate> updates = new java.util.WeakHashMap<>();

    private static final AtomicBoolean installed = new AtomicBoolean();

    private static final String HOVER_MARKER = "tormozit.foldingCommentHover"; //$NON-NLS-1$

    private BslEditorFoldingHook()
    {
    }

    /** Предпросмотр на кнопке свёртки оставляем только для блоков комментариев (#805). */
    static void installCommentHover(ISourceViewer sourceViewer)
    {
        if (!(sourceViewer instanceof ProjectionViewer viewer))
            return;
        wrapProjectionHover(viewer);
        FoldingTrace.install(viewer);
        if (Boolean.TRUE.equals(viewer.getData(HOVER_MARKER)))
            return;
        viewer.setData(HOVER_MARKER, Boolean.TRUE);
        viewer.addProjectionListener(new IProjectionListener()
        {
            @Override public void projectionEnabled()
            {
                wrapProjectionHover(viewer);
                FoldingTrace.install(viewer);
            }
            @Override public void projectionDisabled() {}
        });
    }

    /** Расчёт без блокировки UI; штатная замена аннотаций — только для той же версии текста. */
    private static final class FoldingUpdate
    {
        private final java.lang.ref.WeakReference<Object> provider;
        private final java.lang.ref.WeakReference<ProjectionViewer> viewer;
        private final java.lang.ref.WeakReference<org.eclipse.xtext.ui.editor.model.IXtextDocument> document;
        private final java.lang.reflect.Method filter;
        private final java.lang.reflect.Method merge;
        private final java.lang.reflect.Method update;
        private final java.lang.reflect.Method peek;
        private final org.eclipse.swt.widgets.Display display;
        private long requested;
        private long completed;
        private final org.eclipse.core.runtime.jobs.Job job;

        FoldingUpdate(Object provider, ProjectionViewer viewer,
            org.eclipse.xtext.ui.editor.model.IXtextDocument document) throws ReflectiveOperationException
        {
            this.provider = new java.lang.ref.WeakReference<>(provider);
            this.viewer = new java.lang.ref.WeakReference<>(viewer);
            this.document = new java.lang.ref.WeakReference<>(document);
            // Вызов приходит и из фонового notify: getDefault не обращается к SWT-контролу.
            display = org.eclipse.swt.widgets.Display.getDefault();
            Class<?> type = org.eclipse.xtext.ui.editor.folding.DefaultFoldingStructureProvider.class;
            filter = type.getDeclaredMethod("filterFoldedPositions", java.util.Collection.class); //$NON-NLS-1$
            merge = type.getDeclaredMethod("mergeFoldingRegions", java.util.Collection.class, ProjectionAnnotationModel.class); //$NON-NLS-1$
            update = type.getDeclaredMethod("updateFoldingRegions", boolean.class, ProjectionAnnotationModel.class, //$NON-NLS-1$
                java.util.Collection.class, Annotation[].class);
            filter.setAccessible(true);
            merge.setAccessible(true);
            update.setAccessible(true);
            peek = document.getClass().getMethod("readOnlyDataModelWithoutSync", org.eclipse.xtext.util.concurrent.IUnitOfWork.class); //$NON-NLS-1$
            job = org.eclipse.core.runtime.jobs.Job.create("Комфорт: обновление свёрток BSL", monitor -> { //$NON-NLS-1$
                return calculate(monitor);
            });
            job.setSystem(true);
            job.setPriority(org.eclipse.core.runtime.jobs.Job.DECORATE);
            job.addJobChangeListener(new org.eclipse.core.runtime.jobs.JobChangeAdapter()
            {
                @Override
                public void done(org.eclipse.core.runtime.jobs.IJobChangeEvent event)
                {
                    synchronized (FoldingUpdate.this)
                    {
                        if (requested != completed && FoldingUpdate.this.provider.get() != null)
                            job.schedule();
                    }
                }
            });
        }

        synchronized void request()
        {
            requested++;
            Global.tempLog("folding-update", "request generation=" + requested); //$NON-NLS-1$ //$NON-NLS-2$
            if (job.getState() == org.eclipse.core.runtime.jobs.Job.NONE)
                job.schedule();
        }

        private synchronized long generation()
        {
            return requested;
        }

        private org.eclipse.core.runtime.IStatus calculate(org.eclipse.core.runtime.IProgressMonitor monitor)
        {
            long generation = generation();
            Global.tempLog("folding-update", "begin generation=" + generation); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                Object target = provider.get();
                ProjectionViewer source = viewer.get();
                org.eclipse.xtext.ui.editor.model.IXtextDocument doc = document.get();
                if (target == null || source == null || doc == null || Global.getField(target, "editor") == null) //$NON-NLS-1$
                    return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                java.util.function.BooleanSupplier obsolete = () -> monitor.isCanceled()
                    || Global.getField(target, "editor") == null; //$NON-NLS-1$
                while (!obsolete.getAsBoolean())
                {
                    generation = generation();
                    if (!ContentAssistSessionReloader.waitModelReadyUpTo(doc, Integer.MAX_VALUE, obsolete))
                        return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                    Calculation calculated = (Calculation) peek.invoke(doc,
                        (org.eclipse.xtext.util.concurrent.IUnitOfWork<Calculation, org.eclipse.xtext.resource.XtextResource>) resource -> {
                        long stamp = ((org.eclipse.jface.text.IDocumentExtension4) doc).getModificationStamp();
                        String text = doc.get();
                        if (resource == null || resource.getParseResult() == null || resource.getParseResult().getRootNode() == null
                            || !text.equals(resource.getParseResult().getRootNode().getText()))
                            return null;
                        // То же условие, что в DefaultFoldingStructureProvider.modelChanged:
                        // при синтаксической ошибке существующие аннотации остаются на месте.
                        for (org.eclipse.emf.ecore.resource.Resource.Diagnostic error : resource.getErrors())
                            if (error instanceof org.eclipse.xtext.resource.XtextSyntaxDiagnostic)
                                return new Calculation(stamp, null);
                        Object regions = Global.getField(target, "foldingRegionProvider"); //$NON-NLS-1$
                        if (!(regions instanceof org.eclipse.xtext.ui.editor.folding.IFoldingRegionProvider nativeRegions))
                            throw new IllegalStateException("No native folding region provider"); //$NON-NLS-1$
                        java.util.Collection<org.eclipse.xtext.ui.editor.folding.FoldedPosition> positions =
                            nativeRegions.getFoldingRegions(doc);
                        if (stamp != ((org.eclipse.jface.text.IDocumentExtension4) doc).getModificationStamp()
                            || !text.equals(doc.get()))
                            return null;
                        return new Calculation(stamp, new java.util.LinkedHashSet<>(positions));
                    });
                    if (calculated == null)
                    {
                        Global.tempLog("folding-update", "retry inconsistent generation=" + generation); //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    }
                    if (calculated.positions == null)
                    {
                        Global.tempLog("folding-update", "keep syntax errors generation=" + generation); //$NON-NLS-1$ //$NON-NLS-2$
                        return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                    }
                    final long readyGeneration = generation;
                    if (display.isDisposed())
                        return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                    display.asyncExec(() -> publish(target, source, doc, readyGeneration, calculated));
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                }
                return org.eclipse.core.runtime.Status.CANCEL_STATUS;
            }
            catch (Exception | LinkageError e)
            {
                Global.tempLogException("folding-update", "calculate generation=" + generation, e); //$NON-NLS-1$ //$NON-NLS-2$
                return org.eclipse.core.runtime.Status.CANCEL_STATUS;
            }
            finally
            {
                synchronized (this)
                {
                    completed = generation;
                }
                Global.tempLog("folding-update", "end generation=" + generation); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        @SuppressWarnings("unchecked")
        private void publish(Object target, ProjectionViewer source,
            org.eclipse.xtext.ui.editor.model.IXtextDocument doc, long generation, Calculation calculated)
        {
            if (source.getTextWidget() == null || source.getTextWidget().isDisposed()
                || source.getDocument() != doc || Global.getField(target, "editor") == null) //$NON-NLS-1$
                return;
            if (generation != generation())
            {
                Global.tempLog("folding-update", "drop newer request generation=" + generation); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (((org.eclipse.jface.text.IDocumentExtension4) doc).getModificationStamp() != calculated.stamp)
            {
                Global.tempLog("folding-update", "drop changed document generation=" + generation); //$NON-NLS-1$ //$NON-NLS-2$
                request();
                return;
            }
            ProjectionAnnotationModel model = source.getProjectionAnnotationModel();
            if (model == null)
                return;
            try
            {
                java.util.Collection<org.eclipse.xtext.ui.editor.folding.FoldedPosition> positions =
                    (java.util.Collection<org.eclipse.xtext.ui.editor.folding.FoldedPosition>) filter.invoke(target, calculated.positions);
                Annotation[] removed = (Annotation[]) merge.invoke(target, positions, model);
                update.invoke(target, false, model, positions, removed);
                Global.tempLog("folding-update", "publish generation=" + generation + " stamp=" + calculated.stamp //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " removed=" + removed.length + " added=" + positions.size()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                Global.tempLogException("folding-update", "publish generation=" + generation, e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private record Calculation(long stamp, java.util.Collection<org.eclipse.xtext.ui.editor.folding.FoldedPosition> positions) {}
    }

    private static Boolean requestFoldingUpdate(Object provider, Boolean initialize)
    {
        if (Boolean.TRUE.equals(initialize) || provider == null || !provider.getClass().getName().equals(
            "com._1c.g5.v8.dt.bsl.ui.folding.BslFoldingStructureProvider")) //$NON-NLS-1$
            return Boolean.FALSE;
        try
        {
            synchronized (updates)
            {
                Object source = Global.getField(provider, "viewer"); //$NON-NLS-1$
                if (!(source instanceof ProjectionViewer viewer)
                    || !(viewer.getDocument() instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument doc))
                    return Boolean.TRUE;
                FoldingUpdate current = updates.get(provider);
                if (current == null || current.viewer.get() != viewer || current.document.get() != doc)
                {
                    if (current != null)
                        current.job.cancel();
                    current = new FoldingUpdate(provider, viewer, doc);
                    updates.put(provider, current);
                }
                current.request();
            }
        }
        catch (Exception | LinkageError e)
        {
            Global.tempLogException("folding-update", "request", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return Boolean.TRUE;
    }

    /** Временная диагностика раскрытия свёрток; состояние модели не меняет. */
    private static final class FoldingTrace implements
        org.eclipse.jface.text.source.IAnnotationModelListener,
        org.eclipse.jface.text.source.IAnnotationModelListenerExtension,
        org.eclipse.jface.text.IDocumentListener
    {
        private static final String KEY = "tormozit.foldingTrace"; //$NON-NLS-1$
        private final ProjectionAnnotationModel model;
        private final IDocument document;
        private final java.util.Map<Annotation, Boolean> states = new java.util.IdentityHashMap<>();
        private volatile String lastEdit = "none"; //$NON-NLS-1$

        private FoldingTrace(ProjectionAnnotationModel model, IDocument document)
        {
            this.model = model;
            this.document = document;
        }

        static void install(ProjectionViewer viewer)
        {
            ProjectionAnnotationModel model = viewer.getProjectionAnnotationModel();
            Object previous = viewer.getData(KEY);
            if (previous instanceof FoldingTrace trace)
            {
                if (trace.model == model && trace.document == viewer.getDocument())
                    return;
                trace.model.removeAnnotationModelListener(trace);
                trace.document.removeDocumentListener(trace);
            }
            if (model == null)
                return;
            FoldingTrace trace = new FoldingTrace(model, viewer.getDocument());
            viewer.setData(KEY, trace);
            trace.document.addDocumentListener(trace);
            model.addAnnotationModelListener(trace);
            if (previous == null)
                viewer.getTextWidget().addDisposeListener(event -> {
                    if (viewer.getData(KEY) instanceof FoldingTrace current)
                    {
                        current.model.removeAnnotationModelListener(current);
                        current.document.removeDocumentListener(current);
                    }
                });
        }

        @Override
        public void documentAboutToBeChanged(org.eclipse.jface.text.DocumentEvent event)
        {
            lastEdit = "offset=" + event.getOffset() + " replaced=" + event.getLength() //$NON-NLS-1$ //$NON-NLS-2$
                + " inserted=" + (event.getText() == null ? 0 : event.getText().length()); //$NON-NLS-1$
            Global.tempLog("folding-document", "before " + lastEdit + " stamp=" + stamp() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " text=" + escaped(event.getText())); //$NON-NLS-1$
        }

        @Override
        public void documentChanged(org.eclipse.jface.text.DocumentEvent event)
        {
            Global.tempLog("folding-document", "after offset=" + event.getOffset() + " stamp=" + stamp()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        private long stamp()
        {
            return document instanceof org.eclipse.jface.text.IDocumentExtension4 stamped
                ? stamped.getModificationStamp() : -1;
        }

        @Override
        public synchronized void modelChanged(org.eclipse.jface.text.source.IAnnotationModel changed)
        {
            StringBuilder details = new StringBuilder("reset model=") //$NON-NLS-1$
                .append(System.identityHashCode(model));
            states.clear();
            Iterator<Annotation> iterator = model.getAnnotationIterator();
            while (iterator.hasNext())
            {
                Annotation annotation = iterator.next();
                record(details, " current", annotation, model.getPosition(annotation)); //$NON-NLS-1$
            }
            log(details);
        }

        @Override
        public synchronized void modelChanged(org.eclipse.jface.text.source.AnnotationModelEvent event)
        {
            if (event.isWorldChange())
            {
                modelChanged(event.getAnnotationModel());
                return;
            }
            StringBuilder details = new StringBuilder("event model=") //$NON-NLS-1$
                .append(System.identityHashCode(model))
                .append(" removed=").append(event.getRemovedAnnotations().length) //$NON-NLS-1$
                .append(" added=").append(event.getAddedAnnotations().length) //$NON-NLS-1$
                .append(" changed=").append(event.getChangedAnnotations().length); //$NON-NLS-1$
            for (Annotation annotation : event.getRemovedAnnotations())
            {
                record(details, " remove", annotation, event.getPositionOfRemovedAnnotation(annotation)); //$NON-NLS-1$
                states.remove(annotation);
            }
            for (Annotation annotation : event.getAddedAnnotations())
                record(details, " add", annotation, model.getPosition(annotation)); //$NON-NLS-1$
            for (Annotation annotation : event.getChangedAnnotations())
                record(details, " change", annotation, model.getPosition(annotation)); //$NON-NLS-1$
            log(details);
        }

        private void record(StringBuilder details, String kind, Annotation annotation, Position position)
        {
            if (annotation instanceof ProjectionAnnotation projection)
            {
                Boolean before = states.put(annotation, projection.isCollapsed());
                details.append(kind).append(" id=").append(System.identityHashCode(annotation)) //$NON-NLS-1$
                    .append(" collapsed=").append(before).append("->").append(projection.isCollapsed()) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(" range=").append(position == null ? "null" //$NON-NLS-1$ //$NON-NLS-2$
                        : position.getOffset() + ":" + position.getLength()); //$NON-NLS-1$
                if (position != null)
                {
                    try
                    {
                        int offset = position.getOffset();
                        int line = document.getLineOfOffset(offset);
                        int column = offset - document.getLineOffset(line);
                        int from = Math.max(0, offset - 4);
                        int length = Math.min(64, document.getLength() - from);
                        details.append(" line=").append(line + 1).append(" column=").append(column) //$NON-NLS-1$ //$NON-NLS-2$
                            .append(" around=").append(escaped(document.get(from, length))); //$NON-NLS-1$
                    }
                    catch (BadLocationException e)
                    {
                        details.append(" locationError=").append(e); //$NON-NLS-1$
                    }
                }
            }
        }

        private static String escaped(String text)
        {
            return text == null ? "null" : text.replace("\r", "\\r").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }

        private void log(StringBuilder details)
        {
            long before = stamp();
            try
            {
                Object resource = Global.getField(document, "resource"); //$NON-NLS-1$
                if (resource instanceof org.eclipse.xtext.resource.XtextResource xtext)
                {
                    org.eclipse.xtext.parser.IParseResult parse = xtext.getParseResult();
                    String modelText = parse == null || parse.getRootNode() == null
                        ? null : parse.getRootNode().getText();
                    details.append(" textMatch=").append(modelText != null && modelText.equals(document.get())) //$NON-NLS-1$
                        .append(" modelLength=").append(modelText == null ? -1 : modelText.length()); //$NON-NLS-1$
                }
            }
            catch (RuntimeException e)
            {
                details.append(" modelError=").append(e); //$NON-NLS-1$
            }
            details.append(" stampBefore=").append(before).append(" stampAfter=").append(stamp()) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" lastEdit={").append(lastEdit).append('}') //$NON-NLS-1$
                .append(" updatePatch=").append(System.getProperty(PROP_FOLDING_UPDATE + ".patch")); //$NON-NLS-1$ //$NON-NLS-2$
            details.append(" thread=").append(Thread.currentThread().getName()).append(" stack="); //$NON-NLS-1$ //$NON-NLS-2$
            for (StackTraceElement frame : Thread.currentThread().getStackTrace())
                details.append(frame).append(" | "); //$NON-NLS-1$
            Global.tempLog("folding-state", details.toString()); //$NON-NLS-1$
        }
    }

    private static void wrapProjectionHover(ProjectionViewer viewer)
    {
        // Поле SourceViewer и обход колонок — как в QuickDiffHook.
        if (!(Global.getField(viewer, "fVerticalRuler") instanceof CompositeRuler ruler)) //$NON-NLS-1$
            return;
        Iterator<?> columns = ruler.getDecoratorIterator();
        while (columns.hasNext())
        {
            // ProjectionRulerColumn имеет package visibility; методы hover доступны
            // через его публичный базовый класс AnnotationRulerColumn.
            if (columns.next() instanceof AnnotationRulerColumn column
                && column.getClass().getName().equals(
                    "org.eclipse.jface.text.source.projection.ProjectionRulerColumn")) //$NON-NLS-1$
            {
                IAnnotationHover hover = column.getHover();
                if (!(hover instanceof CommentProjectionHover)
                    && hover instanceof IAnnotationHoverExtension extension
                    && hover instanceof IInformationProviderExtension2 presenter)
                    column.setHover(new CommentProjectionHover(hover, extension, presenter));
            }
        }
    }

    private static final class CommentProjectionHover
        implements IAnnotationHover, IAnnotationHoverExtension, IInformationProviderExtension2
    {
        private final IAnnotationHover delegate;
        private final IAnnotationHoverExtension extension;
        private final IInformationProviderExtension2 presenter;

        CommentProjectionHover(IAnnotationHover delegate, IAnnotationHoverExtension extension,
            IInformationProviderExtension2 presenter)
        {
            this.delegate = delegate;
            this.extension = extension;
            this.presenter = presenter;
        }

        @Override
        public String getHoverInfo(ISourceViewer viewer, int line)
        {
            return isCommentFold(viewer, line) ? delegate.getHoverInfo(viewer, line) : null;
        }

        @Override
        public Object getHoverInfo(ISourceViewer viewer, ILineRange range, int visibleLines)
        {
            return isCommentFold(viewer, range.getStartLine())
                ? extension.getHoverInfo(viewer, range, visibleLines) : null;
        }

        @Override
        public ILineRange getHoverLineRange(ISourceViewer viewer, int line)
        {
            return isCommentFold(viewer, line) ? extension.getHoverLineRange(viewer, line) : null;
        }

        @Override public boolean canHandleMouseCursor() { return extension.canHandleMouseCursor(); }
        @Override public IInformationControlCreator getHoverControlCreator()
        {
            return extension.getHoverControlCreator();
        }
        @Override public IInformationControlCreator getInformationPresenterControlCreator()
        {
            return presenter.getInformationPresenterControlCreator();
        }

        private static boolean isCommentFold(ISourceViewer sourceViewer, int line)
        {
            if (!(sourceViewer instanceof ProjectionViewer viewer))
                return false;
            ProjectionAnnotationModel model = viewer.getProjectionAnnotationModel();
            IDocument document = viewer.getDocument();
            if (model == null || document == null)
                return false;
            // Тот же выбор свёртки и строки заголовка, что у ProjectionAnnotationHover.
            Iterator<Annotation> annotations = model.getAnnotationIterator();
            while (annotations.hasNext())
            {
                Annotation annotation = annotations.next();
                if (!(annotation instanceof ProjectionAnnotation projection) || !projection.isCollapsed())
                    continue;
                Position position = model.getPosition(annotation);
                if (position == null || position.isDeleted() || position.getOffset() < 0
                    || position.getLength() <= 0)
                    continue;
                try
                {
                    int caption = position instanceof IProjectionPosition folding
                        ? folding.computeCaptionOffset(document) : 0;
                    if (document.getLineOfOffset(position.getOffset() + caption) != line)
                        continue;
                    // Проверяем весь блок: начало метода может включать его комментарий,
                    // а штатная подсказка возвращает лишь первые visibleLines строк.
                    int first = document.getLineOfOffset(position.getOffset());
                    int last = document.getLineOfOffset(position.getOffset() + position.getLength() - 1);
                    boolean comment = false;
                    for (int current = first; current <= last; current++)
                    {
                        IRegion info = document.getLineInformation(current);
                        String text = document.get(info.getOffset(), info.getLength()).stripLeading();
                        if (text.isBlank())
                            continue;
                        if (!text.startsWith("//")) //$NON-NLS-1$
                            return false;
                        comment = true;
                    }
                    return comment;
                }
                catch (BadLocationException e)
                {
                    return false;
                }
            }
            return false;
        }
    }

    /** Регистрация функции для вплетённого кода; как можно раньше из {@code Activator.start}. */
    public static void install()
    {
        if (!installed.compareAndSet(false, true))
            return;
        System.getProperties().put(PROP_INITIALLY_COLLAPSED,
            (Function<Object, Object>) BslEditorFoldingHook::isInitiallyCollapsed);
        System.getProperties().put(PROP_FOLDING_UPDATE,
            (java.util.function.BiFunction<Object, Boolean, Boolean>) BslEditorFoldingHook::requestFoldingUpdate);
    }

    /** Вызов из вплетённого {@code isInitiallyCollapsed}: {@code element → TRUE/FALSE}. */
    public static Object isInitiallyCollapsed(Object element)
    {
        try
        {
            if (!(element instanceof RegionPreprocessor region))
                return Boolean.FALSE;
            Set<String> names = ComfortSettings.getAutoCollapseRegionNames();
            String name = region.getName();
            if (names.isEmpty() || name == null || !names.contains(name.toLowerCase(Locale.ROOT)))
                return Boolean.FALSE;
            // Область с запомненной кареткой не сворачиваем: восстановление позиции
            // (BslModulePositionMemoryHook) её тут же развернёт.
            return !containsSavedCaret(region);
        }
        catch (Throwable t)
        {
            // Любой сбой — штатное поведение EDT, свёртки не должны ломаться.
            return Boolean.FALSE;
        }
    }

    private static boolean containsSavedCaret(RegionPreprocessor region)
    {
        ICompositeNode node = NodeModelUtils.getNode(region);
        URI uri = region.eResource() != null ? region.eResource().getURI() : null;
        if (node == null || uri == null || !uri.isPlatformResource())
            return false;
        IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(
            new org.eclipse.core.runtime.Path(uri.toPlatformString(true)));
        int caretLine = BslModulePositionMemoryHook.savedCaretLine(file) + 1; // строки узла — с 1
        if (caretLine <= 0)
            return false;
        int endOffset = BracketContentHintIndex.resolveEndOffset(region, node);
        int endLine = endOffset > node.getOffset()
            ? NodeModelUtils.getLineAndColumn(node, endOffset - 1).getLine()
            : node.getStartLine();
        return caretLine >= node.getStartLine() && caretLine <= endLine;
    }
}
