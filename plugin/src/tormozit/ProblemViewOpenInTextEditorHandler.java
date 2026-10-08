package tormozit;

import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.core.runtime.NullProgressMonitor;
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
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;

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
        openMarker(marker);
        return null;
    }

    static void openMarker(Marker marker)
    {
        if (marker == null)
            return;
        // Function-перегрузка задаётся переменной явно: у provideObject есть ещё Consumer-вариант,
        // и с method reference обе оказались бы применимы (ambiguous).
        Function<EObject, IFile> fileResolver = ProblemViewOpenInTextEditorHandler::resolveSourceFile;
        IFile file = marker.provideObject(fileResolver);
        if (file == null && marker.getProject() != null)
        {
            Function<EObject, Boolean> configuration = object -> object != null
                && com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.CONFIGURATION.isSuperTypeOf(object.eClass());
            if (Boolean.TRUE.equals(marker.provideObject(configuration)))
                file = marker.getProject().getFile("src/Configuration/Configuration.mdo");
        }
        if (file == null || !file.exists())
            return;

        Function<EObject, String> nameResolver = ProblemViewOpenInTextEditorHandler::nearestName;
        String objectName = marker.provideObject(nameResolver);
        Function<EObject, SourceReference> referenceResolver = object -> sourceReference(object, marker);
        SourceReference reference = marker.provideObject(referenceResolver);
        openInEditor(file, objectName, reference);
    }

    /** Открывает Configuration.mdo и выделяет ссылку {@code referenceFqn} в свойстве {@code property}. */
    static void openConfigurationReference(IProject project, String property, String referenceFqn)
    {
        IFile file = project.getFile("src/Configuration/Configuration.mdo");
        if (file.exists())
            openInEditor(file, null, new SourceReference(property, -1, referenceFqn));
    }

    private record SourceReference(String property, int index, String value) {}

    private static SourceReference sourceReference(EObject object, Marker marker)
    {
        if (object == null
            || !com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.CONFIGURATION.isSuperTypeOf(object.eClass())
            || !(ProblemViewOpenTargetHook.resolveFeature(object, marker) instanceof EReference feature))
            return null;
        Integer itemIndex = StandardExtraInfo.MODEL_ITEM_INDEX.get(marker);
        var broken = MdReferenceSupport.findBrokenReferences(object, feature, new NullProgressMonitor());
        MdReferenceSupport.BrokenReference target = broken.stream()
            .filter(item -> itemIndex != null && item.index() == itemIndex).findFirst()
            .orElse(broken.isEmpty() ? null : broken.get(0));
        return new SourceReference(feature.getName(), target != null ? target.index() : itemIndex != null ? itemIndex : -1,
            target != null ? MdReferenceSupport.fullName(EcoreUtil.getURI(target.target())) : null);
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

    private static void openInEditor(IFile file, String objectName, SourceReference reference)
    {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        if (page == null)
            return;
        String ext = file.getFileExtension();
        String editorId = !"Configuration.mdo".equals(file.getName())
            && ext != null && XML_SOURCE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))
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
        if (reference != null)
            revealReference(editor, reference);
        else
            revealObjectName(editor, objectName);
    }

    private static void revealReference(IEditorPart editor, SourceReference reference)
    {
        ITextEditor textEditor = findTextEditor(editor);
        if (textEditor == null || textEditor.getDocumentProvider() == null)
            return;
        IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        if (document == null)
            return;
        Display.getDefault().asyncExec(() ->
        {
            int[] range = findReferenceRange(document.get(), reference);
            Global.tempLog("broken-links-problem", "reveal property=" + reference.property()
                + " index=" + reference.index() + " reference=" + reference.value()
                + " offset=" + (range != null ? range[0] : -1));
            if (range != null)
                textEditor.selectAndReveal(range[0], range[1]);
        });
    }

    /** Прямые свойства Configuration: комментарии и одноимённые вложенные элементы не учитываются. */
    private static int[] findReferenceRange(String text, SourceReference reference)
    {
        var tokens = Pattern.compile("(?s)<!--.*?-->|<!\\[CDATA\\[.*?\\]\\]>|<\\?.*?\\?>|<![^>]*>"
            + "|<(/?)([\\w:.-]+)(?:\"[^\"]*\"|'[^']*'|[^'\">])*>").matcher(text);
        String expected = reference.value() != null
            ? reference.value().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : null;
        int depth = 0;
        int index = -1;
        int start = -1;
        int[] first = null;
        int[] indexed = null;
        int[] named = null;
        while (tokens.find())
        {
            if (tokens.group(2) == null)
                continue;
            boolean closing = "/".equals(tokens.group(1));
            boolean property = reference.property().equals(tokens.group(2));
            if (closing)
            {
                if (depth == 2 && property && start >= 0)
                {
                    int end = tokens.start();
                    while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                    while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
                    int[] range = new int[] { start, end - start };
                    if (first == null) first = range;
                    if (index == reference.index()) indexed = range;
                    if (expected != null && text.regionMatches(start, expected, 0, expected.length())
                        && end - start == expected.length())
                    {
                        if (index == reference.index() || reference.index() < 0)
                            return range;
                        if (named == null) named = range;
                    }
                    start = -1;
                }
                depth--;
            }
            else if (!tokens.group().endsWith("/>"))
            {
                if (depth == 1 && property)
                {
                    index++;
                    start = tokens.end();
                }
                depth++;
            }
        }
        return named != null ? named : indexed != null ? indexed : first;
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
