package tormozit;

import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.validation.marker.Marker;

/**
 * «Открыть в текстовом редакторе» в панели «Ошибки конфигурации» — открывает исходный файл
 * объекта ошибки (для формы это {@code Form.form}) в редакторе XML, как одноимённая команда
 * в результатах поиска по файлам (см. {@code FileSearchResultsHook}).
 */
public class ProblemViewOpenInTextEditorHandler extends AbstractHandler
{

    /** ID встроенного в Eclipse простого текстового редактора; литералом — как в {@code FileSearchResultsHook}. */
    private static final String DEFAULT_TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor"; //$NON-NLS-1$

    /** «Открыть с помощью → Редактор XML» (org.eclipse.wst.xml.ui). */
    private static final String XML_EDITOR_ID =
        "org.eclipse.wst.xml.ui.internal.tabletree.XMLMultiPageEditorPart"; //$NON-NLS-1$

    private static final Set<String> XML_SOURCE_EXTENSIONS = Set.of(
        "form", "cmi", "xml", "mxlx", "mdo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /** Как в {@code org.eclipse.ui.actions.OpenWithMenu}: не переиспользовать чужой редактор по тому же input. */
    private static final int OPEN_WITH_MATCH =
        IWorkbenchPage.MATCH_INPUT | IWorkbenchPage.MATCH_ID | IWorkbenchPage.MATCH_IGNORE_SIZE;

    @Override
    public Object execute(ExecutionEvent event)
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        Marker marker = ProblemViewMarkers.firstSelectedMarker(part);
        if (marker == null)
            return null;
        // Function-перегрузка задаётся переменной явно: у provideObject есть ещё Consumer-вариант,
        // и с method reference обе оказались бы применимы (ambiguous).
        Function<EObject, IFile> fileResolver = ProblemViewOpenInTextEditorHandler::resolveSourceFile;
        IFile file = marker.provideObject(fileResolver);
        if (file == null || !file.exists())
            return null;

        Function<EObject, String> nameResolver = ProblemViewOpenInTextEditorHandler::nearestName;
        String objectName = marker.provideObject(nameResolver);
        openInEditor(file, objectName);
        return null;
    }

    /** Имя ближайшего именованного объекта — по нему ищется место в XML. */
    private static String nearestName(EObject object)
    {
        for (EObject current = object; current != null; current = current.eContainer())
        {
            if (current instanceof NamedElement named)
            {
                String name = named.getName();
                if (name != null && !name.isBlank())
                    return name;
            }
        }
        return null;
    }

    /** Файл-исходник объекта ошибки; для вложенных объектов поднимается вверх по контейнерам. */
    private static IFile resolveSourceFile(EObject object)
    {
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        if (lookup == null)
            return null;
        for (EObject current = object; current != null; current = current.eContainer())
        {
            IFile file = lookup.getPlatformResource(current);
            if (file != null && file.exists())
                return file;
        }
        return null;
    }

    private static void openInEditor(IFile file, String objectName)
    {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        if (page == null)
            return;
        String ext = file.getFileExtension();
        String editorId = ext != null && XML_SOURCE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))
            ? XML_EDITOR_ID : DEFAULT_TEXT_EDITOR_ID;
        IEditorInput input = new FileEditorInput(file);
        IEditorPart editor;
        try
        {
            editor = page.openEditor(input, editorId, true, OPEN_WITH_MATCH);
        }
        catch (PartInitException e)
        {
            return;
        }
        revealObjectName(editor, objectName);
    }

    /**
     * Выделяет в открытом тексте имя объекта ошибки — обычно это {@code <name>Команда1</name>}
     * в XML формы, то есть каретка встаёт ровно на нужном элементе, а не в начале файла.
     */
    private static void revealObjectName(IEditorPart editor, String objectName)
    {
        if (editor == null || objectName == null || objectName.isBlank())
            return;
        ITextEditor textEditor = findTextEditor(editor);
        if (textEditor == null)
            return;
        IDocument document = textEditor.getDocumentProvider() != null
            ? textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput()) : null;
        if (document == null)
            return;
        String text = document.get();
        // сначала точное вхождение как значения XML-элемента, иначе — первое вхождение имени
        int offset = text.indexOf(">" + objectName + "<"); //$NON-NLS-1$ //$NON-NLS-2$
        offset = offset >= 0 ? offset + 1 : text.indexOf(objectName);
        if (offset < 0)
        {
            return;
        }
        int selectionOffset = offset;
        Display.getDefault().asyncExec(() -> textEditor.selectAndReveal(selectionOffset, objectName.length()));
    }

    /** Текстовая страница внутри многостраничного редактора XML. */
    private static ITextEditor findTextEditor(IEditorPart editor)
    {
        if (editor instanceof ITextEditor textEditor)
            return textEditor;
        ITextEditor adapted = editor.getAdapter(ITextEditor.class);
        if (adapted != null)
            return adapted;
        Object countObject = Global.invoke(editor, "getPageCount"); //$NON-NLS-1$
        if (!(countObject instanceof Integer count))
            return null;
        for (int i = 0; i < count; i++)
        {
            Object pageObject = Global.invoke(editor, "getEditor", Integer.valueOf(i)); //$NON-NLS-1$
            if (pageObject instanceof IEditorPart pageEditor)
            {
                ITextEditor nested = findTextEditor(pageEditor);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }
}
