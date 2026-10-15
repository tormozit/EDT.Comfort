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
        if (Boolean.TRUE.equals(viewer.getData(HOVER_MARKER)))
            return;
        viewer.setData(HOVER_MARKER, Boolean.TRUE);
        viewer.addProjectionListener(new IProjectionListener()
        {
            @Override public void projectionEnabled() { wrapProjectionHover(viewer); }
            @Override public void projectionDisabled() {}
        });
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
