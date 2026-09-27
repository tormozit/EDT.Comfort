package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelListener;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Сворачивание областей ({@code #Область}) модуля BSL, имена которых заданы в настройке
 * «Автоматически сворачиваемые области» (issue #527): при открытии модуля и по команде
 * «Сбросить сворачиваемые группы» ({@link ResetFoldingHandler}).
 *
 * <p>Xtext заполняет {@link ProjectionAnnotationModel} асинхронно после разбора модуля, поэтому
 * сворачивание — одноразовый слушатель модели аннотаций: срабатывает на первое заполнение
 * и снимается. Область определяется по первой строке свёртки ({@code #Область Имя}).
 */
public final class BslEditorFoldingHook implements IStartup
{
    private static final String ATTACHED_MARKER = "tormozit.bslAutoCollapseAttached"; //$NON-NLS-1$
    private static final int MAX_ATTACH_ATTEMPTS = 100;
    private static final Pattern REGION_LINE =
        Pattern.compile("^\\s*#\\s*(?:Область|Region)\\s+(\\S.*?)\\s*$", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Set<IWorkbenchWindow> hookedWindows =
        Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<DtGranularEditor<?>> hookedGranular =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> {
            IWorkbench workbench = PlatformUI.getWorkbench();
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
            workbench.addWindowListener(new IWindowListener()
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
                    hookedWindows.remove(window);
                }
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || !hookedWindows.add(window))
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart part = ref.getEditor(false);
                if (part != null)
                    hookEditor(part);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookReference(ref);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                hookReference(ref);
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void hookReference(IWorkbenchPartReference ref)
    {
        if (ref instanceof IEditorReference editorRef)
        {
            IEditorPart part = editorRef.getEditor(false);
            if (part != null)
                hookEditor(part);
        }
    }

    private static void hookEditor(IEditorPart part)
    {
        BslXtextEditor bsl = GetRef.getActiveBslEditor(part);
        if (bsl != null)
            Display.getDefault().asyncExec(() -> attach(bsl, 0));
        else if (part instanceof DtGranularEditor<?> granular && hookedGranular.add(granular))
        {
            granular.addPageChangedListener(event -> {
                BslXtextEditor embedded = GetRef.getActiveBslEditor(granular);
                if (embedded != null)
                    Display.getDefault().asyncExec(() -> attach(embedded, 0));
            });
        }
    }

    private static void attach(BslXtextEditor editor, int attempt)
    {
        if (editor.getSite() == null || !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
            return;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof ProjectionViewer projectionViewer))
        {
            if (attempt < MAX_ATTACH_ATTEMPTS)
                Display.getDefault().asyncExec(() -> attach(editor, attempt + 1));
            return;
        }
        StyledText widget = projectionViewer.getTextWidget();
        if (widget == null || widget.isDisposed() || Boolean.TRUE.equals(widget.getData(ATTACHED_MARKER)))
            return;
        widget.setData(ATTACHED_MARKER, Boolean.TRUE);
        collapseWhenPopulated(editor);
    }

    /**
     * Свернуть настроенные области при первом заполнении модели свёрток (или сразу, если она уже
     * заполнена). Слушатель одноразовый и снимается при закрытии редактора.
     */
    static void collapseWhenPopulated(BslXtextEditor editor)
    {
        if (ComfortSettings.getAutoCollapseRegionNames().isEmpty())
            return;
        if (!(editor.getInternalSourceViewer() instanceof ProjectionViewer viewer))
            return;
        ProjectionAnnotationModel model = viewer.getProjectionAnnotationModel();
        StyledText widget = viewer.getTextWidget();
        if (model == null || widget == null || widget.isDisposed())
            return;
        if (collapseNow(editor, viewer, model))
            return;

        boolean[] done = {false};
        IAnnotationModelListener listener = new IAnnotationModelListener()
        {
            @Override
            public void modelChanged(IAnnotationModel changed)
            {
                Display.getDefault().asyncExec(() -> {
                    if (done[0] || widget.isDisposed())
                        return;
                    if (collapseNow(editor, viewer, model))
                    {
                        done[0] = true;
                        model.removeAnnotationModelListener(this);
                    }
                });
            }
        };
        model.addAnnotationModelListener(listener);
        widget.addDisposeListener(e -> model.removeAnnotationModelListener(listener));
    }

    /**
     * @return {@code true}, если в модели уже есть свёртки (то есть заполнение произошло) —
     *         независимо от того, нашлись ли подходящие области.
     */
    private static boolean collapseNow(BslXtextEditor editor, ProjectionViewer viewer,
        ProjectionAnnotationModel model)
    {
        Set<String> names = ComfortSettings.getAutoCollapseRegionNames();
        IDocument document = viewer.getDocument();
        if (document == null)
            return false;
        // Область с кареткой (текущей или запомненной, которую восстановит BslModulePositionMemoryHook)
        // не сворачиваем: иначе восстановление позиции сразу её развернёт.
        int[] caretOffsets = {
            viewer.getSelectedRange().x > 0 ? viewer.getSelectedRange().x : -1,
            BslModulePositionMemoryHook.savedCaretOffset(editor, document) };
        boolean populated = false;
        List<Annotation> toCollapse = new ArrayList<>();
        Iterator<?> it = model.getAnnotationIterator();
        while (it.hasNext())
        {
            if (!(it.next() instanceof ProjectionAnnotation annotation))
                continue;
            populated = true;
            Position position = model.getPosition(annotation);
            if (position != null && !annotation.isCollapsed() && !containsAny(position, caretOffsets)
                && isConfiguredRegion(document, position, names))
                toCollapse.add(annotation);
        }
        for (Annotation annotation : toCollapse)
            model.collapse(annotation);
        return populated;
    }

    private static boolean containsAny(Position position, int[] offsets)
    {
        for (int offset : offsets)
        {
            if (offset >= 0 && offset >= position.getOffset() && offset <= position.getOffset() + position.getLength())
                return true;
        }
        return false;
    }

    private static boolean isConfiguredRegion(IDocument document, Position position, Set<String> names)
    {
        try
        {
            IRegion line = document.getLineInformationOfOffset(position.getOffset());
            Matcher matcher = REGION_LINE.matcher(document.get(line.getOffset(), line.getLength()));
            return matcher.matches() && names.contains(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        catch (BadLocationException e)
        {
            return false;
        }
    }
}
