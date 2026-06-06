package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.xtext.resource.IResourceServiceProvider;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import com._1c.g5.v8.dt.ui.editor.IDtEditor;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;

public class GetRef extends AbstractHandler
{
    // =========================================================================
    // Маппинги
    // =========================================================================

    private static final Map<String, String> SUBFOLDER_TO_RU = new LinkedHashMap<>();
    static
    {
        SUBFOLDER_TO_RU.put("Forms",          "Форма");
        SUBFOLDER_TO_RU.put("Templates",      "Макет");
        SUBFOLDER_TO_RU.put("Commands",       "Команда");
        SUBFOLDER_TO_RU.put("Recalculations", "Перерасчет");
    }

    private static final Set<String> TOP_LEVEL_CONTAINERS = new HashSet<>(Arrays.asList(
        "CommonModules", "CommonForms", "CommonTemplates", "CommonPictures", "CommonCommands"
    ));

    private static final Pattern METHOD_START = Pattern.compile(
        "^\\s*(?:Асинх\\s+)?(?:Процедура|Функция|Procedure|Function)\\s+" +
        "([А-ЯЁа-яёA-Za-z_][А-ЯЁа-яёA-Za-z0-9_]*)",
        Pattern.UNICODE_CASE);

    private static final Pattern METHOD_END = Pattern.compile(
        "^\\s*(?:КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)\\b",
        Pattern.UNICODE_CASE);

    // =========================================================================
    // IHandler
    // =========================================================================

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        Shell          shell = HandlerUtil.getActiveShell(event);
        BslXtextEditor bslEditor = getActiveBslEditor(part);
        if (bslEditor != null) { showModuleLineRefs(bslEditor, shell); return null; }
        String ref = getRefFromPart(part);
        if (ref == null || ref.isBlank())
        {
            ToastNotification.show("Ссылка",
                "Не удалось определить имя объекта метаданных.\n"
                + "Выберите узел в навигаторе, дереве сравнения или откройте редактор объекта.",
                5000);
            return null;
        }

        Global.log("GetRef: " + ref); //$NON-NLS-1$
        setClipboardText(ref, shell);
        ToastNotification.show("Скопирована ссылка", ref, 6000);
        return null;
    }

    // =========================================================================
    // Режим 1: ссылки на строку модуля BSL
    // =========================================================================

    private static void showModuleLineRefs(BslXtextEditor bslEditor, Shell shell)
    {
        ModuleLineContext ctx = computeModuleLineContext(bslEditor);
        if (ctx == null)
        {
            IEditorInput input = bslEditor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                {
                    String ref = pathToFullName(file.getProjectRelativePath().toString());
                    if (ref != null)
                    { setClipboardText(ref, shell); ToastNotification.show("Скопирована ссылка", ref, 6000); return; }
                }
            }
            ToastNotification.show("Ссылка", "Не удалось определить путь к модулю", 5000);
            return;
        }

        String ref1 = ctx.buildRef1(true);
        String docRef = ctx.getModuleDocRef();

        Global.log("GetRef (line ref): " + ref1); //$NON-NLS-1$
        setClipboardText(ref1, shell);
        ToastNotification.show("Скопировано", ref1);

        if (docRef != null)
        {
            final String docRefCopy = docRef;
            ToastNotification.show("Имя метода", docRef, 4_000,
                () -> copyToClipboard(docRefCopy), "Копировать"); //$NON-NLS-1$
        }
    }

    /**
     * Формирует расширенную ссылку на текущую строку в BSL-редакторе.
     * Используется также в {@link EditEmbeddedTextHandler#editEmbeddedText}.
     */
    public static String buildExtendedRef(BslXtextEditor bslEditor, boolean addLineText)
    {
        ModuleLineContext ctx = computeModuleLineContext(bslEditor);
        return ctx != null ? ctx.buildRef1(addLineText) : null;
    }

    private static ModuleLineContext computeModuleLineContext(BslXtextEditor bslEditor)
    {
        IEditorInput input = bslEditor.getEditorInput();
        if (input == null) return null;
        IFile file = input.getAdapter(IFile.class);
        if (file == null) return null;

        ModuleRef moduleRef = pathToModuleRef(file.getProjectRelativePath().toString());
        if (moduleRef == null) return null;

        ISourceViewer viewer = bslEditor.getInternalSourceViewer();
        if (viewer == null) return null;
        IDocument doc = viewer.getDocument();
        if (doc == null) return null;

        Object selObj = viewer.getSelectionProvider().getSelection();
        if (!(selObj instanceof ITextSelection)) return null;
        ITextSelection textSel = (ITextSelection) selObj;

        try
        {
            int    line0      = doc.getLineOfOffset(textSel.getOffset());
            int    lineNumber = line0 + 1;
            IRegion li        = doc.getLineInformation(line0);
            String markedLine = doc.get(li.getOffset(), li.getLength()).stripTrailing();
            markedLine = markedLine.substring(0, Math.min(100, markedLine.length()));
            MethodInfo method = findEnclosingMethod(doc, line0);
            int columnNumber = textSel.getOffset() - doc.getLineOffset(line0);
            return new ModuleLineContext(moduleRef, lineNumber, columnNumber, markedLine, method);
        }
        catch (BadLocationException e)
        {
            Global.log("GetRef.computeModuleLineContext: " + e); //$NON-NLS-1$
            return null;
        }
    }

    public static BslXtextEditor getActiveBslEditor(IWorkbenchPart part)
    {
        IWorkbenchPage page  = part.getSite().getPage();
        if (part instanceof BslXtextEditor) return (BslXtextEditor) part;
        IEditorPart editor = page != null ? page.getActiveEditor() : null;
        if (editor instanceof DtGranularEditor<?>)
        {
            IFormPage activePage = ((DtGranularEditor<?>) editor).getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
            {
                IEditorPart embedded =
                    ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor) return (BslXtextEditor) embedded;
            }
        }
        if (editor instanceof BslXtextEditor) return (BslXtextEditor) editor;
        return null;
    }

    // =========================================================================
    // Конвертация пути файла → путь модуля для ссылок
    // =========================================================================

    static ModuleRef pathToModuleRef(String projectRelativePath)
    {
        if (projectRelativePath == null) return null;
        String path = projectRelativePath.replace('\\', '/');
        String extensionName = null;
        String relative;

        if (path.startsWith("src/ext/")) //$NON-NLS-1$
        {
            String rest = path.substring("src/ext/".length()); //$NON-NLS-1$
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            extensionName = rest.substring(0, slash);
            relative = rest.substring(slash + 1);
        }
        else if (path.startsWith("src/")) //$NON-NLS-1$
            relative = path.substring("src/".length()); //$NON-NLS-1$
        else
            return null;

        String[] p = relative.split("/", -1); //$NON-NLS-1$
        if (p.length < 3) return null;

        String rootTypeRu = MdTypeMapping.folderToRu(p[0]);
        if (rootTypeRu == null) return null;

        StringBuilder ref = new StringBuilder();
        ref.append(rootTypeRu).append('.').append(p[1]);

        for (int i = 2; i < p.length - 1; i += 2)
        {
            if (i + 1 >= p.length - 1) return null;
            String containerRu = MdTypeMapping.folderToRu(p[i]);
            if (containerRu == null) return null;
            ref.append('.').append(containerRu).append('.').append(p[i + 1]);
        }

        String fileType    = new Path(p[p.length - 1]).removeFileExtension().toString();
        boolean isForm     = "CommonForms".equals(p[0]) //$NON-NLS-1$
                             || (p.length >= 5 && "Forms".equals(p[p.length - 3])); //$NON-NLS-1$
        String moduleTypeRu = isForm ? "Форма" : MdTypeMapping.enSingToRu(fileType); //$NON-NLS-1$
        if (moduleTypeRu == null) return null;

        ref.append('.').append(moduleTypeRu);
        return new ModuleRef(extensionName, ref.toString());
    }

    private static String stripModuleSuffix(String modulePath)
    {
        int lastDot = modulePath.lastIndexOf('.');
        if (lastDot < 0) return modulePath;
        if (modulePath.indexOf('.') == lastDot) return modulePath;
        String last = modulePath.substring(lastDot + 1);
        return MdTypeMapping.isModuleTypeSuffix(last) ? modulePath.substring(0, lastDot) : modulePath;
    }

    // =========================================================================
    // Поиск объемлющего метода
    // =========================================================================

    private static MethodInfo findEnclosingMethod(IDocument doc, int cursorLine0)
    {
        for (int line = cursorLine0; line >= 0; line--)
        {
            String text;
            try
            {
                IRegion info = doc.getLineInformation(line);
                text = doc.get(info.getOffset(), info.getLength());
            }
            catch (BadLocationException e) { break; }
            if (line < cursorLine0 && METHOD_END.matcher(text).find()) return null;
            Matcher m = METHOD_START.matcher(text);
            if (m.find()) return new MethodInfo(m.group(1), line + 1);
        }
        return null;
    }

    // =========================================================================
    // Режим 2: имя объекта МД
    // =========================================================================

    public static String getRefFromPart(IWorkbenchPart part)
    {
        IWorkbenchPage page  = part.getSite().getPage();
        if (part instanceof IEditorPart
                && Global.COMPARE_EDITOR_ID.equals(part.getSite().getId()))
            return refFromCompareEditor((IEditorPart) part);
        if (part == page.findView(Global.NAVIGATOR_VIEW_ID))
            return refFromNavigator(page);
//        if (part == page.findView(Global.PROPERTIES_SHEET_ID)) // не работает
//            return null; // TODO
        String ref = getRefFromEditor(page);
        if (ref != null) return ref;
        return refFromNavigator(page);
    }

    private static String refFromCompareEditor(IEditorPart editor)
    {
        ISelection sel = CompareConfigOpenObjectHandler.getSelection(editor);
        if (!(sel instanceof IStructuredSelection)) return null;
        Object element = ((IStructuredSelection) sel).getFirstElement();
        if (element == null) return null;
        MatchedObjectsComparisonNode node = CompareConfigSelectionListener.resolveMatchedNode(element);
        if (node == null) return null;
        IComparisonSession session = CompareConfigSelectionListener.getSession(editor);
        if (session == null) return null;
        Long bmId = node.getMainObjectId();
        if (bmId == null || bmId == -1L) bmId = node.getOtherObjectId();
        if (bmId == null || bmId == -1L) return null;
        return eObjectToFullName(CompareConfigOpenObjectHandler.getEObject(session, bmId, node));
    }

    public static String getRefFromEditor(Object pageOrEditor)
    {
        IEditorPart editor;
        if (pageOrEditor instanceof IEditorPart)
            editor = (IEditorPart)pageOrEditor;
        else
        {
            editor = ((IWorkbenchPage)pageOrEditor).getActiveEditor();
//            if (editor == null) editor = ((IWorkbenchPage)pageOrEditor).getActivePart();
            if (editor == null) return null;
        }
        if (editor instanceof DtGranularEditor<?>)
        {
            String ref = refFromGranularEditor((DtGranularEditor<?>) editor);
            if (ref != null) return ref;
        }
        return refFromEditorInput(editor.getEditorInput());
    }

    private static String refFromGranularEditor(DtGranularEditor<?> editor)
    {
        IFormPage activePage = editor.getActivePageInstance();
        // У вложенных страниц (макет, модуль …) getModel() — объект страницы, не корень редактора.
        String ref = refFromDtEditorModel(activePage);
        if (ref != null) return ref;

        if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
        {
            ref = refFromEmbeddedEditor(
                ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor());
            if (ref != null) return ref;
        }
        else if (activePage instanceof DtGranularEditorEmbeddedEditorPage<?>)
        {
            ref = refFromEmbeddedEditor(
                ((DtGranularEditorEmbeddedEditorPage<?>) activePage).getEmbeddedEditor());
            if (ref != null) return ref;
        }

        ref = refFromDtEditorModel(editor);
        if (ref != null) return ref;
        return refFromEditorInput(editor.getEditorInput());
    }

    @SuppressWarnings("rawtypes")
    private static String refFromDtEditorModel(Object dtEditorOrPage)
    {
        if (dtEditorOrPage instanceof IDtEditor)
        {
            try
            {
                IDtEditor dtEditor = (IDtEditor) dtEditorOrPage;
                String ref = refFromEObjectModel(dtEditor.getModel());
                if (ref != null) return ref;
                Object input = dtEditor.getEditorInput();
                if (input instanceof IDtEditorInput)
                {
                    ref = refFromEObjectModel(((IDtEditorInput) input).getModel());
                    if (ref != null) return ref;
                }
            }
            catch (Exception e) { Global.log("GetRef.refFromDtEditorModel: " + e); } //$NON-NLS-1$
        }
        for (String field : new String[] { "mdObject", "topObject", "modelObject" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object value = Global.getField(dtEditorOrPage, field);
            String ref = refFromEObjectModel(value);
            if (ref != null) return ref;
        }
        return null;
    }

    private static String refFromEObjectModel(Object model)
    {
        if (!(model instanceof EObject)) return null;
        String best = null;
        for (EObject o = (EObject) model; o != null; o = o.eContainer())
        {
            String ref = eObjectToFullName(o);
            if (ref == null) continue;
            if (best == null || ref.length() > best.length()) best = ref;
        }
        return best;
    }

    private static String refFromEmbeddedEditor(IEditorPart embedded)
    {
        if (embedded == null) return null;
        return refFromEditorInput(embedded.getEditorInput());
    }

    private static String refFromEditorInput(IEditorInput input)
    {
        if (input == null) return null;
        IFile file = input.getAdapter(IFile.class);
        if (file == null) return null;
        return pathToFullName(file.getProjectRelativePath().toString());
    }

    private static String refFromNavigator(IWorkbenchPage page)
    {
        if (page == null) return null;
        try
        {
            CommonNavigator nav = (CommonNavigator) page.findView(Global.NAVIGATOR_VIEW_ID);
            if (nav == null) return null;
            IStructuredSelection sel = (IStructuredSelection)
                nav.getSite().getSelectionProvider().getSelection();
            if (sel == null || sel.isEmpty()) return null;
            return refFromElement(sel.getFirstElement());
        }
        catch (Exception e) { Global.log("GetRef.refFromNavigator: " + e); return null; } //$NON-NLS-1$
    }

    private static String refFromElement(Object element)
    {
        if (element == null) return null;
        if (element instanceof EObject) { String r = eObjectToFullName((EObject) element); if (r != null) return r; }
        IFile file = Adapters.adapt(element, IFile.class);
        if (file != null) { String r = pathToFullName(file.getProjectRelativePath().toString()); if (r != null) return r; }
        IResource resource = Adapters.adapt(element, IResource.class);
        if (resource != null && resource.getType() != IResource.PROJECT)
        { String r = pathToFullName(resource.getProjectRelativePath().toString()); if (r != null) return r; }
        for (String getter : new String[]{ "getFile", "getResource", "getMdObject" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object result = Global.call(element, getter);
            if (result instanceof EObject) { String r = eObjectToFullName((EObject) result); if (r != null) return r; }
            if (result instanceof IFile) { String r = pathToFullName(((IFile) result).getProjectRelativePath().toString()); if (r != null) return r; }
        }
        return null;
    }

    // =========================================================================
    // EObject → полное имя МД
    // =========================================================================

    static String eObjectToFullName(EObject obj)
    {
        if (obj == null) return null;
        Resource emfResource = obj.eResource();
        if (emfResource == null) return null;
        URI uri = emfResource.getURI();
        if ("bm".equals(uri.scheme())) //$NON-NLS-1$
        {
            String fqnQnp = getFqnViaQnp(obj, uri);
            if (fqnQnp != null) { String r = MdTypeMapping.bmFqnToRuFullName(fqnQnp); if (r != null) return r; }
            String uriPath = uri.path();
            if (uriPath != null)
            {
                if (uriPath.startsWith("/")) uriPath = uriPath.substring(1); //$NON-NLS-1$
                String r = MdTypeMapping.bmFqnToRuFullName(uriPath); if (r != null) return r;
            }
            return null;
        }
        if (uri.isPlatformResource())
        {
            String pp = uri.toPlatformString(true); if (pp == null) return null;
            int s = pp.indexOf('/', 1); if (s < 0) return null;
            return pathToFullName(pp.substring(s + 1));
        }
        return null;
    }

    private static String getFqnViaQnp(EObject obj, URI uri)
    {
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
            if (rsp == null) return null;
            org.eclipse.xtext.naming.IQualifiedNameProvider qnp =
                rsp.get(org.eclipse.xtext.naming.IQualifiedNameProvider.class);
            if (qnp == null) return null;
            org.eclipse.xtext.naming.QualifiedName fqn = qnp.getFullyQualifiedName(obj);
            return fqn != null ? fqn.toString() : null;
        }
        catch (Exception e) { Global.log("GetRef.getFqnViaQnp: " + e); return null; } //$NON-NLS-1$
    }

    // =========================================================================
    // Путь файла EDT → полное русское имя МД
    // =========================================================================

    static String pathToFullName(String projectRelativePath)
    {
        if (projectRelativePath == null) return null;
        String path = projectRelativePath.replace('\\', '/');
        String extensionName = null;
        String relative;
        if (path.startsWith("src/ext/")) //$NON-NLS-1$
        {
            String rest = path.substring("src/ext/".length()); //$NON-NLS-1$
            int slash = rest.indexOf('/'); if (slash < 0) return null;
            extensionName = rest.substring(0, slash); relative = rest.substring(slash + 1);
        }
        else if (path.startsWith("src/")) //$NON-NLS-1$
            relative = path.substring("src/".length()); //$NON-NLS-1$
        else return null;

        String[] p = relative.split("/", -1); //$NON-NLS-1$
        if (p.length < 1 || p[0].isEmpty()) return null;
        String typeRu = MdTypeMapping.folderToRu(p[0]); if (typeRu == null) return null;
        if (p.length < 2 || p[1].isEmpty()) return withExt(extensionName, typeRu);
        String objectName = stripFileExt(p[1]);
        String base = withExt(extensionName, typeRu + "." + objectName); //$NON-NLS-1$
        if (p.length == 2) return base;
        String seg2 = p[2];
        if (seg2.endsWith(".mdo") && stripFileExt(seg2).equals(objectName)) return base; //$NON-NLS-1$
        if ("Ext".equals(seg2)) //$NON-NLS-1$
        {
            if (p.length < 4) return base;
            if (TOP_LEVEL_CONTAINERS.contains(p[0])) return base;
            String moduleSuffix = MdTypeMapping.bslFilenameToModuleRu(p[3]);
            return moduleSuffix != null ? base + "." + moduleSuffix : base; //$NON-NLS-1$
        }
        String sectionRu = SUBFOLDER_TO_RU.get(seg2);
        if (sectionRu != null)
        {
            if (p.length < 4 || p[3].isEmpty()) return base + "." + sectionRu; //$NON-NLS-1$
            return base + "." + sectionRu + "." + stripFileExt(p[3]); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String nested = pathToFullNameFromNestedFolders(p, base);
        if (nested != null) return nested;
        return base;
    }

    /** Ищет Templates/Forms/… не только в p[2] (глубокий путь к файлу макета). */
    private static String pathToFullNameFromNestedFolders(String[] p, String base)
    {
        for (int i = 2; i < p.length - 1; i++)
        {
            String sectionRu = SUBFOLDER_TO_RU.get(p[i]);
            if (sectionRu == null) continue;
            String item = stripFileExt(p[i + 1]);
            if (!item.isEmpty()) return base + "." + sectionRu + "." + item; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static String withExt(String ext, String name) { return ext != null ? ext + " " + name : name; } //$NON-NLS-1$
    private static String stripFileExt(String name)
    {
        if (name.endsWith(".mdo")) return name.substring(0, name.length() - 4); //$NON-NLS-1$
        if (name.endsWith(".bsl")) return name.substring(0, name.length() - 4); //$NON-NLS-1$
        return name;
    }

    // =========================================================================
    // Буфер обмена
    // =========================================================================

    private static void setClipboardText(String text, Shell shell)
    {
        Clipboard cb = new Clipboard(shell.getDisplay());
        try { cb.setContents(new Object[]{ text }, new Transfer[]{ TextTransfer.getInstance() }); }
        finally { cb.dispose(); }
    }

    private static void copyToClipboard(String text)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return;
        Clipboard cb = new Clipboard(display);
        try { cb.setContents(new Object[]{ text }, new Transfer[]{ TextTransfer.getInstance() }); }
        finally { cb.dispose(); }
    }

    // =========================================================================
    // Вспомогательные классы
    // =========================================================================

    private static final class ModuleLineContext
    {
        final ModuleRef  moduleRef;
        final int        lineNumber;
        final int        columnNumber;
        final String     markedLine;
        final MethodInfo method;

        ModuleLineContext(ModuleRef moduleRef, int lineNumber, int columnNumber, String markedLine, MethodInfo method)
        {
            this.moduleRef = moduleRef; 
            this.lineNumber = lineNumber; 
            this.columnNumber = columnNumber; 
            this.markedLine = markedLine;
            this.method = method;
        }

        /** Расширенная ссылка строки модуля (ссылка 1). */
        String buildRef1(boolean addLineText)
        {
            String prefix = moduleRef.toRefPrefix();
            String lineText = "";
            if (addLineText)
            {
                lineText = ": " + markedLine;
            }
            if (method != null)
            {
                int offset = lineNumber - method.declarationLine1;
                return "{" + prefix + "(" + lineNumber + "," + columnNumber + ":" + method.name + "," + offset + ")}" + lineText; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            }
            return "{" + prefix + "(" + lineNumber + "," + columnNumber + ")}" + lineText; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        /**
         * Строит ссылку на метод для документирующих комментариев.
         * Использует {@link MdTypeMapping#directModuleName} — порт функции
         * {@code ирОбщий.ПрямоеИмяМодуляИзПолного} из приложения ИР.
         *
         * <pre>
         *   "Справочник.Валюты.МодульОбъекта" + "МойМетод"   → "см. СправочникОбъект.Валюты.МойМетод"
         *   "Справочник.Валюты.МодульМенеджера" + "НайтиПоКоду" → "см. Справочники.Валюты.НайтиПоКоду"
         *   "ОбщийМодуль.МойМодуль" + "МетодМодуля"          → "см. МойМодуль.МетодМодуля"
         * </pre>
         *
         * @return ссылка вида «см. ПрямоеИмя.МетодИмя», или {@code null}
         *         если курсор вне метода или прямое имя не определено
         */
        String getModuleDocRef()
        {
            if (method == null) return null;
            String directName = MdTypeMapping.directModuleName(moduleRef.modulePath);
            if (directName == null || directName.isBlank()) return null;
            return "см. " + directName + "." + method.name; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    static final class ModuleRef
    {
        final String extensionName;
        final String modulePath;

        ModuleRef(String ext, String path) { extensionName=ext; modulePath=path; }

        String toRefPrefix()
        { return extensionName != null ? extensionName + " " + modulePath : modulePath; } //$NON-NLS-1$
    }

    private static final class MethodInfo
    {
        final String name;
        final int    declarationLine1;
        MethodInfo(String n, int l) { name=n; declarationLine1=l; }
    }
}
