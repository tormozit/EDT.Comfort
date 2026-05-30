
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Глобальная команда «Ссылка».
 *
 * <h3>Режим 1: BSL-редактор активен → ссылки на строку модуля (2 уведомления)</h3>
 * <ol>
 *   <li><b>Расширенная ссылка</b> — помещается в буфер обмена и показывается
 *       первым уведомлением. Поддерживается только в ИР:
 *       <pre>{[РасшИмя ]Тип.Объект.Модуль(НомерСтроки:ИмяМетода,СмещениеВМетоде)}: ТекстСтроки</pre>
 *       Также передаётся в {@link EditEmbeddedTextHandler#editEmbeddedText} как
 *       идентификатор позиции для возврата отредактированного текста из ИР.</li>
 *   <li><b>Полное имя метода</b> — второе уведомление с кнопкой «Копировать»
 *       (только если курсор внутри метода). Для документирующих комментариев:
 *       <pre>см. Тип.Объект.Форма.ИмяФормы.ИмяМетода</pre></li>
 * </ol>
 *
 * <h3>Режим 2: навигатор / compare editor / другой редактор → имя объекта МД</h3>
 * Пример: {@code Справочник.Валюты.Макет.Классификатор}
 */
public class GetRef extends AbstractHandler
{
    // =========================================================================
    // Маппинги
    // =========================================================================

    /** EDT-папка под-объекта → русское название раздела. */
    private static final Map<String, String> SUBFOLDER_TO_RU = new LinkedHashMap<>();
    static
    {
        SUBFOLDER_TO_RU.put("Forms",          "Форма");
        SUBFOLDER_TO_RU.put("Templates",      "Макет");
        SUBFOLDER_TO_RU.put("Commands",       "Команда");
        SUBFOLDER_TO_RU.put("Recalculations", "Перерасчет");
    }

    /** Имя BSL-файла в Ext/ → русский суффикс в полном имени объекта. */
    private static final Map<String, String> BSL_TO_MODULE_RU = new LinkedHashMap<>();
    static
    {
        BSL_TO_MODULE_RU.put("ObjectModule.bsl",    "МодульОбъекта");
        BSL_TO_MODULE_RU.put("ManagerModule.bsl",   "МодульМенеджера");
        BSL_TO_MODULE_RU.put("RecordSetModule.bsl", "МодульНабораЗаписей");
        BSL_TO_MODULE_RU.put("Module.bsl",          "Модуль");
    }

    /** Папки, где Ext-файл является самим объектом (суффикс модуля не добавляется). */
    private static final Set<String> TOP_LEVEL_CONTAINERS = new HashSet<>(Arrays.asList(
        "CommonModules", "CommonForms", "CommonTemplates", "CommonPictures", "CommonCommands"
    ));

    /** Суффиксы типа модуля — убираются при построении ссылки на метод (3+ сегментов). */
    private static final Set<String> MODULE_TYPE_SUFFIXES = new HashSet<>(Arrays.asList(
        "МодульОбъекта", "МодульМенеджера", "МодульНабораЗаписей", "Модуль", "Форма"
    ));

    /** Строка объявления Процедуры/Функции. */
    private static final Pattern METHOD_START = Pattern.compile(
        "^\\s*(?:Асинх\\s+)?(?:Процедура|Функция|Procedure|Function)\\s+" +
        "([А-ЯЁа-яёA-Za-z_][А-ЯЁа-яёA-Za-z0-9_]*)",
        Pattern.UNICODE_CASE);

    /** Строка завершения Процедуры/Функции. */
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
        IWorkbenchPage page  = part.getSite().getPage();
        Shell          shell = HandlerUtil.getActiveShell(event);

        BslXtextEditor bslEditor = getActiveBslEditor(part, page);
        if (bslEditor != null)
        {
            showModuleLineRefs(bslEditor, shell);
            return null;
        }

        String ref = resolveRef(part, page);
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

    /**
     * Показывает уведомления с расширенной ссылкой и именем метода.
     * Расширенная ссылка сразу помещается в буфер обмена.
     */
    private static void showModuleLineRefs(BslXtextEditor bslEditor, Shell shell)
    {
        ModuleLineContext ctx = computeModuleLineContext(bslEditor);

        if (ctx == null)
        {
            // Fallback: показываем имя объекта или ошибку
            IEditorInput input = bslEditor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                {
                    String ref = pathToFullName(file.getProjectRelativePath().toString());
                    if (ref != null)
                    {
                        setClipboardText(ref, shell);
                        ToastNotification.show("Скопирована ссылка", ref, 6000);
                        return;
                    }
                }
            }
            ToastNotification.show("Ссылка", "Не удалось определить путь к модулю", 5000);
            return;
        }

        String ref1 = ctx.buildRef1();
        String ref3 = ctx.buildRef3();

        Global.log("GetRef (line ref): " + ref1); //$NON-NLS-1$
        setClipboardText(ref1, shell);
        ToastNotification.show("Скопировано", ref1);

        if (ref3 != null)
        {
            final String ref3Copy = ref3;
            ToastNotification.show("Имя метода", ref3, 4_000,
                () -> copyToClipboard(ref3Copy), "Копировать"); //$NON-NLS-1$
        }
    }

    /**
     * Формирует расширенную ссылку на текущую строку в BSL-редакторе.
     *
     * <p>Расширенная ссылка включает имя метода и смещение внутри него,
     * что позволяет корректно перейти к строке даже если метод был перемещён.
     *
     * <p>Используется как в {@link #showModuleLineRefs} (для показа уведомления
     * и копирования в буфер обмена), так и в
     * {@link EditEmbeddedTextHandler#editEmbeddedText} (для передачи позиции
     * в ИР при открытии редактора вложенного текста).
     *
     * <pre>
     *   Пример: {ИнструментыTormozit Обработка.ирКонсоль.Форма.Форма.Форма(113:МойМетод,7)}: ТекстСтроки
     * </pre>
     *
     * @return расширенная ссылка строки, или {@code null} если не удалось вычислить
     */
    public static String buildExtendedRef(BslXtextEditor bslEditor)
    {
        ModuleLineContext ctx = computeModuleLineContext(bslEditor);
        return ctx != null ? ctx.buildRef1() : null;
    }

    /**
     * Собирает все данные, необходимые для построения ссылок на строку модуля.
     *
     * @return контекст или {@code null} если редактор не находится в BSL-модуле проекта
     */
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

            return new ModuleLineContext(moduleRef, lineNumber, markedLine, method);
        }
        catch (BadLocationException e)
        {
            Global.log("GetRef.computeModuleLineContext: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static BslXtextEditor getActiveBslEditor(IWorkbenchPart part, IWorkbenchPage page)
    {
        if (part instanceof BslXtextEditor) return (BslXtextEditor) part;

        IEditorPart editor = page != null ? page.getActiveEditor() : null;

        if (editor instanceof DtGranularEditor<?>)
        {
            IFormPage activePage = ((DtGranularEditor<?>) editor).getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
            {
                IEditorPart embedded =
                    ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor)
                    return (BslXtextEditor) embedded;
            }
        }

        if (editor instanceof BslXtextEditor) return (BslXtextEditor) editor;
        return null;
    }

    // =========================================================================
    // Конвертация пути файла → путь модуля для ссылок
    // =========================================================================

    /**
     * Разбирает project-relative путь BSL-файла и возвращает {@link ModuleRef}.
     *
     * <h3>Правило для форм</h3>
     * Ссылка на модуль формы <em>всегда</em> заканчивается на {@code ".Форма"}.
     */
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
        boolean isForm     = "CommonForms".equals(p[0])
                             || (p.length >= 5 && "Forms".equals(p[p.length - 3]));
        String moduleTypeRu = isForm ? "Форма" : MdTypeMapping.enSingToRu(fileType); //$NON-NLS-1$
        if (moduleTypeRu == null) return null;

        ref.append('.').append(moduleTypeRu);
        return new ModuleRef(extensionName, ref.toString());
    }

    /** Убирает суффикс типа модуля из конца пути (только для 3+ сегментов). */
    private static String stripModuleSuffix(String modulePath)
    {
        int lastDot = modulePath.lastIndexOf('.');
        if (lastDot < 0) return modulePath;
        if (modulePath.indexOf('.') == lastDot) return modulePath;
        String last = modulePath.substring(lastDot + 1);
        return MODULE_TYPE_SUFFIXES.contains(last) ? modulePath.substring(0, lastDot) : modulePath;
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

    private static String resolveRef(IWorkbenchPart part, IWorkbenchPage page)
    {
        if (part instanceof IEditorPart
                && Global.COMPARE_EDITOR_ID.equals(part.getSite().getId()))
            return refFromCompareEditor((IEditorPart) part);

        if (part == page.findView(Global.NAVIGATOR_VIEW_ID))
            return refFromNavigator(page);

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

    public static String getRefFromEditor(IWorkbenchPage page)
    {
        IEditorPart editor = page != null ? page.getActiveEditor() : null;
        if (editor == null) return null;
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
        if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
        {
            IEditorPart embedded =
                ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
            if (embedded != null)
            {
                String ref = refFromEditorInput(embedded.getEditorInput());
                if (ref != null) return ref;
            }
        }
        return refFromEditorInput(editor.getEditorInput());
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

        if (element instanceof EObject)
        {
            String ref = eObjectToFullName((EObject) element);
            if (ref != null) return ref;
        }
        IFile file = Adapters.adapt(element, IFile.class);
        if (file != null)
        {
            String ref = pathToFullName(file.getProjectRelativePath().toString());
            if (ref != null) return ref;
        }
        IResource resource = Adapters.adapt(element, IResource.class);
        if (resource != null && resource.getType() != IResource.PROJECT)
        {
            String ref = pathToFullName(resource.getProjectRelativePath().toString());
            if (ref != null) return ref;
        }
        for (String getter : new String[]{ "getFile", "getResource", "getMdObject" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object result = Global.call(element, getter);
            if (result instanceof EObject)
            {
                String ref = eObjectToFullName((EObject) result);
                if (ref != null) return ref;
            }
            if (result instanceof IFile)
            {
                String ref = pathToFullName(((IFile) result).getProjectRelativePath().toString());
                if (ref != null) return ref;
            }
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
                String r = MdTypeMapping.bmFqnToRuFullName(uriPath);
                if (r != null) return r;
            }
            return null;
        }
        if (uri.isPlatformResource())
        {
            String pp = uri.toPlatformString(true);
            if (pp == null) return null;
            int s = pp.indexOf('/', 1);
            if (s < 0) return null;
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
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            extensionName = rest.substring(0, slash);
            relative = rest.substring(slash + 1);
        }
        else if (path.startsWith("src/")) //$NON-NLS-1$
            relative = path.substring("src/".length()); //$NON-NLS-1$
        else return null;

        String[] p = relative.split("/", -1); //$NON-NLS-1$
        if (p.length < 1 || p[0].isEmpty()) return null;
        String typeRu = MdTypeMapping.folderToRu(p[0]);
        if (typeRu == null) return null;
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
            String moduleSuffix = BSL_TO_MODULE_RU.get(p[3]);
            return moduleSuffix != null ? base + "." + moduleSuffix : base; //$NON-NLS-1$
        }
        String sectionRu = SUBFOLDER_TO_RU.get(seg2);
        if (sectionRu != null)
        {
            if (p.length < 4 || p[3].isEmpty()) return base + "." + sectionRu; //$NON-NLS-1$
            return base + "." + sectionRu + "." + stripFileExt(p[3]); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return base;
    }

    private static String withExt(String ext, String name)
    {
        return ext != null ? ext + " " + name : name; //$NON-NLS-1$
    }

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

    /**
     * Контекст строки модуля: все данные, вычисленные из BSL-редактора.
     * Используется в {@link #computeModuleLineContext} и позволяет избежать
     * дублирования кода между {@link #showModuleLineRefs} и {@link #buildExtendedRef}.
     */
    private static final class ModuleLineContext
    {
        final ModuleRef  moduleRef;
        final int        lineNumber;   // 1-based
        final String     markedLine;   // текст строки (до 100 символов)
        final MethodInfo method;       // null если курсор вне метода

        ModuleLineContext(ModuleRef moduleRef, int lineNumber,
                          String markedLine, MethodInfo method)
        {
            this.moduleRef  = moduleRef;
            this.lineNumber = lineNumber;
            this.markedLine = markedLine;
            this.method     = method;
        }

        /**
         * Строит расширенную ссылку строки модуля (ссылка 1).
         * <pre>{[РасшИмя ]Тип.Объект.Модуль(НомерСтроки:ИмяМетода,Смещение)}: ТекстСтроки</pre>
         */
        String buildRef1()
        {
            String prefix = moduleRef.toRefPrefix();
            if (method != null)
            {
                int offset = lineNumber - method.declarationLine1;
                return "{" + prefix + "(" + lineNumber + ":" + method.name + "," + offset + ")}: " + markedLine; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            }
            return "{" + prefix + "(" + lineNumber + ")}: " + markedLine; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        /**
         * Строит полное имя метода для документирующих комментариев (ссылка 3).
         * <pre>см. Тип.Объект.Форма.ИмяФормы.ИмяМетода</pre>
         * Возвращает {@code null} если курсор вне метода.
         */
        String buildRef3()
        {
            if (method == null) return null;
            String base = stripModuleSuffix(moduleRef.modulePath);
            return "см. " + base + "." + method.name; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Путь к BSL-модулю в формате ссылок 1С. */
    static final class ModuleRef
    {
        final String extensionName;
        final String modulePath;

        ModuleRef(String extensionName, String modulePath)
        {
            this.extensionName = extensionName;
            this.modulePath    = modulePath;
        }

        String toRefPrefix()
        {
            return extensionName != null ? extensionName + " " + modulePath : modulePath; //$NON-NLS-1$
        }
    }

    private static final class MethodInfo
    {
        final String name;
        final int    declarationLine1; // 1-based

        MethodInfo(String name, int declarationLine1)
        {
            this.name             = name;
            this.declarationLine1 = declarationLine1;
        }
    }
}
