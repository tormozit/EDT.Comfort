package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Команда «Сбросить сворачиваемые группы»: вернуть folding модуля к состоянию
 * как при открытии (после «Свернуть все» / Ctrl+Shift+Num/).
 *
 * <p>Штатный {@code org.eclipse.ui.edit.text.folding.restore} вызывает
 * {@link org.eclipse.xtext.ui.editor.XtextEditor#resetProjection()}, который
 * пересчитывает регионы, но оставляет свёрнутость уже существующих аннотаций.
 * Поэтому после «Свернуть все» начальное состояние не восстанавливается:
 * сначала снимаем projection-аннотации, затем заново инициализируем структуру.
 */
public final class ResetFoldingHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.ResetFolding"; //$NON-NLS-1$
    public static final String BINDING_CONTEXT_ID =
        "com._1c.g5.v8.dt.bsl.ui.editor.BslEditorScope"; //$NON-NLS-1$
    static final String MENU_LABEL = "Сбросить сворачиваемые группы"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        BslXtextEditor editor = resolveBslEditor(HandlerUtil.getActivePart(event));
        if (editor == null)
            editor = resolveBslEditor(HandlerUtil.getActiveEditor(event));
        resetFolding(editor);
        return null;
    }

    public static void resetFolding(BslXtextEditor editor)
    {
        if (editor == null)
        {
            ToastNotification.show(MENU_LABEL, "Откройте модуль BSL и повторите команду", 3000); //$NON-NLS-1$
            return;
        }

        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof ProjectionViewer projectionViewer))
            return;

        ProjectionAnnotationModel model = projectionViewer.getProjectionAnnotationModel();
        if (model == null)
            return;

        clearProjectionAnnotations(model);
        editor.resetProjection();
    }

    private static void clearProjectionAnnotations(ProjectionAnnotationModel model)
    {
        List<Annotation> toRemove = new ArrayList<>();
        Iterator<?> it = model.getAnnotationIterator();
        while (it.hasNext())
        {
            Object next = it.next();
            if (next instanceof ProjectionAnnotation annotation)
                toRemove.add(annotation);
        }
        if (toRemove.isEmpty())
            return;
        model.modifyAnnotations(
            toRemove.toArray(Annotation[]::new),
            Collections.emptyMap(),
            new Annotation[0]);
    }

    private static BslXtextEditor resolveBslEditor(IWorkbenchPart part)
    {
        if (part instanceof IEditorPart editorPart)
            return GetRef.getActiveBslEditor(editorPart);
        return GetRef.getActiveBslEditor(part);
    }
}
