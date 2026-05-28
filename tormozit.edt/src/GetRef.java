
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.xtext.resource.IResourceServiceProvider;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Глобальная команда «Ссылка» — помещает в буфер обмена полное русское имя
 * редактируемого объекта метаданных и показывает всплывающее уведомление.
 *
 * <h3>Поддерживаемые источники</h3>
 * <ol>
 *   <li><b>Дерево сравнения конфигураций</b> — из выбранного узла через EObject.</li>
 *   <li><b>Активный DtGranularEditor</b> — BSL-страница даёт суффикс модуля,
 *       иные страницы — имя объекта без суффикса.</li>
 *   <li><b>Любой редактор с IFile-вводом</b> — BSL / .mdo / .xml файл.</li>
 *   <li><b>Навигатор EDT</b> — EObject, IFile или IResource выбранного узла.</li>
 * </ol>
 *
 * <h3>Два пути к полному имени</h3>
 * <ul>
 *   <li><b>BM URI</b> (EObject из навигатора / дерева сравнения):
 *       {@code bm://Конфигурация/Catalog.Валюты} → FQN {@code "Catalog.Валюты"}
 *       → {@link MdTypeMapping#bmFqnToRuFullName} → {@code "Справочник.Валюты"}.
 *       Для под-объектов используется {@code IQualifiedNameProvider}:
 *       {@code "Catalog.Валюты.Template.Классификатор"} → {@code "Справочник.Валюты.Макет.Классификатор"}.</li>
 *   <li><b>Файловый путь</b> (редакторы, IFile из навигатора):
 *       {@code src/Catalogs/Валюты/Templates/Кл/Кл.mdo} → {@link #pathToFullName}
 *       → {@code "Справочник.Валюты.Макет.Кл"}.</li>
 * </ul>
 */
public class GetRef extends AbstractHandler
{
    // =========================================================================
    // Маппинги (локальные для этого класса)
    // =========================================================================

    /** Папка вложенного объекта EDT → русское название раздела. */
    private static final Map<String, String> SUBFOLDER_TO_RU = new LinkedHashMap<>();
    static
    {
        SUBFOLDER_TO_RU.put("Forms",          "Форма");
        SUBFOLDER_TO_RU.put("Templates",      "Макет");
        SUBFOLDER_TO_RU.put("Commands",       "Команда");
        SUBFOLDER_TO_RU.put("Recalculations", "Перерасчет");
    }

    /** Имя BSL-файла в Ext/ → русский суффикс модуля. */
    private static final Map<String, String> BSL_TO_MODULE_RU = new LinkedHashMap<>();
    static
    {
        BSL_TO_MODULE_RU.put("ObjectModule.bsl",    "МодульОбъекта");
        BSL_TO_MODULE_RU.put("ManagerModule.bsl",   "МодульМенеджера");
        BSL_TO_MODULE_RU.put("RecordSetModule.bsl", "МодульНабораЗаписей");
        BSL_TO_MODULE_RU.put("Module.bsl",          "Модуль");
        // Form.bsl под Forms/<Name>/Ext/ — суффикс не нужен, имя формы уже в пути
    }

    /**
     * Папки верхнего уровня, где Ext-файл является самим объектом, а не его модулем.
     * Суффикс из BSL_TO_MODULE_RU для них не добавляется.
     */
    private static final Set<String> TOP_LEVEL_CONTAINERS = new HashSet<>(Arrays.asList(
        "CommonModules", "CommonForms", "CommonTemplates", "CommonPictures", "CommonCommands"
    ));

    // =========================================================================
    // IHandler
    // =========================================================================

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        Shell shell = HandlerUtil.getActiveShell(event);
        String ref = getRef(part);
        if (ref == null || ref.isBlank())
        {
            ToastNotification.show("Ссылка",
                "Не удалось определить имя объекта метаданных.\n"
                + "Выберите узел в навигаторе, дереве сравнения или откройте редактор объекта.",
                5000);
            return null;
        }
        setClipboardText(ref, shell);
        ToastNotification.show("Скопирована ссылка", ref, 6000);
        return null;
    }

    // =========================================================================
    // Диспетчер источников
    // =========================================================================

    public static String getRef(IWorkbenchPart part)
    {
        IWorkbenchPage page = part.getSite().getPage();
        // 1. Дерево сравнения конфигураций
        if (part instanceof IEditorPart
                && Global.COMPARE_EDITOR_ID.equals(part.getSite().getId()))
            return refFromCompareEditor((IEditorPart) part);

        // 2. Навигатор
        if (part == page.findView(Global.NAVIGATOR_VIEW_ID))
            return refFromNavigator(page);

        // 3. Редактор
        String ref = getRefFromEditor(page);
        if (ref != null) return ref;

        // 4. Fallback: навигатор
        return refFromNavigator(page);
    }

    // =========================================================================
    // Источник 1: дерево сравнения конфигураций
    // =========================================================================

    private static String refFromCompareEditor(IEditorPart editor)
    {
        ISelection sel = CompareConfigOpenObjectHandler.getSelection(editor);
        if (!(sel instanceof IStructuredSelection)) return null;

        Object element = ((IStructuredSelection) sel).getFirstElement();
        if (element == null) return null;

        MatchedObjectsComparisonNode node =
            CompareConfigSelectionListener.resolveMatchedNode(element);
        if (node == null) return null;

        IComparisonSession session = CompareConfigSelectionListener.getSession(editor);
        if (session == null) return null;

        Long bmId = node.getMainObjectId();
        if (bmId == null || bmId == -1L) bmId = node.getOtherObjectId();
        if (bmId == null || bmId == -1L) return null;

        EObject eObject = CompareConfigOpenObjectHandler.getEObject(session, bmId, node);
        return eObjectToFullName(eObject);
    }

    // =========================================================================
    // Источник 2: редактор
    // =========================================================================

    public static String getRefFromEditor(IWorkbenchPage page)
    {
        IEditorPart editor = (page != null) ? page.getActiveEditor() : null;
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

    // =========================================================================
    // Источник 3: навигатор EDT
    // =========================================================================

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
        catch (Exception e)
        {
            Global.log("GetRef.refFromNavigator: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static String refFromElement(Object element)
    {
        if (element == null) return null;

        // EObject → BM URI
        if (element instanceof EObject)
        {
            String ref = eObjectToFullName((EObject) element);
            if (ref != null) return ref;
        }

        // IFile
        IFile file = Adapters.adapt(element, IFile.class);
        if (file != null)
        {
            String ref = pathToFullName(file.getProjectRelativePath().toString());
            if (ref != null) return ref;
        }

        // IResource (IFolder)
        IResource resource = Adapters.adapt(element, IResource.class);
        if (resource != null && resource.getType() != IResource.PROJECT)
        {
            String ref = pathToFullName(resource.getProjectRelativePath().toString());
            if (ref != null) return ref;
        }

        // Рефлексия: EDT-обёртки узлов навигатора
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

    /**
     * Извлекает полное русское имя МД из EMF EObject.
     *
     * <h3>BM URI (основной случай в EDT)</h3>
     * URI имеет вид {@code bm://Конфигурация/Catalog.Валюты}.
     * <ul>
     *   <li><b>Стратегия 1а — IQualifiedNameProvider</b>: самый точный способ,
     *       возвращает полный FQN включая под-объекты, например
     *       {@code "Catalog.Валюты.Template.Классификатор"}.</li>
     *   <li><b>Стратегия 1б — путь URI</b>: {@code uri.path().substring(1)} даёт FQN
     *       корневого объекта — {@code "Catalog.Валюты"}. Работает всегда,
     *       но не различает под-объекты одного родителя.</li>
     * </ul>
     * Оба варианта конвертируются через {@link MdTypeMapping#bmFqnToRuFullName}.
     *
     * <h3>Platform resource URI (запасной случай)</h3>
     * Если URI вдруг окажется {@code platform:/resource/...}, путь парсится через
     * {@link #pathToFullName}.
     */
    static String eObjectToFullName(EObject obj)
    {
        if (obj == null) return null;
        Resource emfResource = obj.eResource();
        if (emfResource == null) return null;
        URI uri = emfResource.getURI();

        // ── BM URI: bm://Конфигурация/Catalog.Валюты ─────────────────────────
        if ("bm".equals(uri.scheme())) //$NON-NLS-1$
        {
            // Стратегия 1а: IQualifiedNameProvider — даёт FQN с под-объектами
            String fqnQnp = getFqnViaQnp(obj, uri);
            if (fqnQnp != null)
            {
                String result = MdTypeMapping.bmFqnToRuFullName(fqnQnp);
                if (result != null) return result;
            }

            // Стратегия 1б: FQN из пути URI (только корневой объект)
            String uriPath = uri.path();
            if (uriPath != null)
            {
                if (uriPath.startsWith("/")) uriPath = uriPath.substring(1); //$NON-NLS-1$
                String result = MdTypeMapping.bmFqnToRuFullName(uriPath);
                if (result != null) return result;
            }

            return null;
        }

        // ── Platform resource URI (запасной случай) ───────────────────────────
        if (uri.isPlatformResource())
        {
            String platformPath = uri.toPlatformString(true); // "/Проект/src/..."
            if (platformPath == null) return null;
            int secondSlash = platformPath.indexOf('/', 1);
            if (secondSlash < 0) return null;
            return pathToFullName(platformPath.substring(secondSlash + 1));
        }

        return null;
    }

    /**
     * Получает FQN EObject через {@link IResourceServiceProvider} →
     * {@code IQualifiedNameProvider}.
     *
     * <p>Для корневых объектов возвращает {@code "Catalog.Валюты"},
     * для под-объектов — {@code "Catalog.Валюты.Template.Классификатор"}.
     * Возвращает {@code null} при любой ошибке или если провайдер недоступен.
     */
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
        catch (Exception e)
        {
            Global.log("GetRef.getFqnViaQnp: " + e); //$NON-NLS-1$
            return null;
        }
    }

    // =========================================================================
    // Ядро: путь в EDT-проекте → полное русское имя МД
    // =========================================================================

    /**
     * Преобразует project-relative путь файла или папки в EDT-проекте в полное русское имя МД.
     *
     * <pre>
     *   src/Catalogs/Валюты/Валюты.mdo               → Справочник.Валюты
     *   src/Catalogs/Валюты/Ext/ObjectModule.bsl      → Справочник.Валюты.МодульОбъекта
     *   src/Catalogs/Валюты/Forms/ФЭ/Ext/Form.bsl     → Справочник.Валюты.Форма.ФЭ
     *   src/Catalogs/Валюты/Templates/Кл/Кл.mdo       → Справочник.Валюты.Макет.Кл
     *   src/CommonModules/МойМод/Ext/Module.bsl        → ОбщийМодуль.МойМод
     *   src/ext/Расш/Catalogs/Валюты/Валюты.mdo        → Расш Справочник.Валюты
     * </pre>
     */
    static String pathToFullName(String projectRelativePath)
    {
        if (projectRelativePath == null) return null;
        String path = projectRelativePath.replace('\\', '/');

        // Разбор префикса: src/ext/<ExtName>/ или src/
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
        {
            relative = path.substring("src/".length()); //$NON-NLS-1$
        }
        else
        {
            return null;
        }

        // Разбор: Folder/Object/[SubKind/SubName/...]
        String[] p = relative.split("/", -1); //$NON-NLS-1$
        if (p.length < 1 || p[0].isEmpty()) return null;

        String typeRu = MdTypeMapping.folderToRu(p[0]);
        if (typeRu == null) return null;

        if (p.length < 2 || p[1].isEmpty())
            return withExt(extensionName, typeRu);

        String objectName = stripFileExt(p[1]);
        String base = withExt(extensionName, typeRu + "." + objectName); //$NON-NLS-1$

        if (p.length == 2) return base;

        String seg2 = p[2];

        // ObjectName.mdo самого объекта
        if (seg2.endsWith(".mdo") && stripFileExt(seg2).equals(objectName)) //$NON-NLS-1$
            return base;

        // Ext/ — модули
        if ("Ext".equals(seg2)) //$NON-NLS-1$
        {
            if (p.length < 4) return base;
            if (TOP_LEVEL_CONTAINERS.contains(p[0])) return base;
            String moduleSuffix = BSL_TO_MODULE_RU.get(p[3]);
            return moduleSuffix != null ? base + "." + moduleSuffix : base; //$NON-NLS-1$
        }

        // Forms / Templates / Commands / Recalculations
        String sectionRu = SUBFOLDER_TO_RU.get(seg2);
        if (sectionRu != null)
        {
            if (p.length < 4 || p[3].isEmpty())
                return base + "." + sectionRu; //$NON-NLS-1$
            return base + "." + sectionRu + "." + stripFileExt(p[3]); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return base;
    }

    private static String withExt(String extensionName, String name)
    {
        return extensionName != null ? extensionName + " " + name : name; //$NON-NLS-1$
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
        try
        {
            cb.setContents(
                new Object[]   { text },
                new Transfer[] { TextTransfer.getInstance() });
        }
        finally { cb.dispose(); }
    }
}
