package tormozit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.plaf.basic.BasicMenuUI;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com._1c.g5.v8.bm.core.BmPlatform;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelEditor;
import com._1c.g5.v8.dt.ui.editor.IDtGranularEditor;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Глобальная команда «Перейти к определению».
 */
public class GoToDefinition extends AbstractHandler
{
    // =======================================================================
    // МАППИНГИ
    // =======================================================================

    /** RU ед.ч. → папка EDT. */
    static final Map<String, String> TYPE_TO_FOLDER = MdTypeMapping.getRuToFolderMap();

    /** Тип+суффикс данных → базовый тип МД. «СправочникСсылка» → «Справочник». */
    static final Map<String, String> TYPE_SUFFIX_MAP = new LinkedHashMap<>();
    static
    {
        addSuffix("Справочник",             "Ссылка", "Объект", "Менеджер", "Выборка",
                                            "МенеджерТаблицы", "Список");
        addSuffix("Документ",               "Ссылка", "Объект", "Менеджер", "Выборка",
                                            "МенеджерТаблицы", "Список");
        addSuffix("Перечисление",           "Ссылка", "Менеджер");
        addSuffix("ПланОбмена",             "Ссылка", "Объект", "Менеджер");
        addSuffix("ПланВидовХарактеристик", "Ссылка", "Объект", "Менеджер");
        addSuffix("ПланСчетов",             "Ссылка", "Объект", "Менеджер");
        addSuffix("ПланВидовРасчета",       "Ссылка", "Объект", "Менеджер");
        addSuffix("БизнесПроцесс",          "Ссылка", "Объект", "Менеджер");
        addSuffix("Задача",                 "Ссылка", "Объект", "Менеджер");
        addSuffix("РегистрСведений",        "НаборЗаписей", "Запись", "Менеджер",
                                            "МенеджерТаблицы", "Ключ");
        addSuffix("РегистрНакопления",      "НаборЗаписей", "Запись", "Менеджер",
                                            "МенеджерТаблицы", "Ключ",
                                            "ВыборкаДетальныеЗаписи", "ВыборкаОстатки",
                                            "ВыборкаОстаткиИОбороты", "ВыборкаОбороты");
        addSuffix("РегистрБухгалтерии",     "НаборЗаписей", "Запись", "Менеджер",
                                            "МенеджерТаблицы", "Ключ");
        addSuffix("РегистрРасчета",         "НаборЗаписей", "Запись", "Менеджер",
                                            "МенеджерТаблицы", "Ключ");
        addSuffix("ВнешняяОбработка",       "Объект");
        addSuffix("ВнешнийОтчет",           "Объект");
    }

    private static void addSuffix(String base, String... suffixes)
    {
        for (String s : suffixes) TYPE_SUFFIX_MAP.put(base + s, base);
    }

    // =======================================================================
    // РЕГУЛЯРНЫЕ ВЫРАЖЕНИЯ
    // =======================================================================

    // Формат ссылки описан тут https://fastcode.im/Templates/8426
    private static final Pattern MODULE_LINE_REF = Pattern.compile(
        "\\{" +
        "(?:([А-ЯЁа-яёA-Za-z0-9]+)\\s)?" +
        "([А-ЯЁа-яёA-Za-z0-9._/\\\\]+)" +
        "\\((\\d+)(?:,(\\d+))?" +
        "(?::([А-ЯЁа-яёA-Za-z_][А-ЯЁа-яёA-Za-z0-9_]*)," +
        "(\\d+))?" +
        "\\)\\}");

    private static final Pattern GIT_FILE_REF = Pattern.compile(
        "(?:src/(?:cf/|ext/[^/]+/)?)?([A-Za-z].+\\.bsl):(\\d+)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern TYPE_DESCRIPTION_SPLITTER = Pattern.compile("\\s*,\\s*");

    // =======================================================================
    // ТОЧКА ВХОДА
    // =======================================================================

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IRSession session = null;
        File newFile = null, oldFile = null;
        String command = "";
        String transportFolder = IRApplication.transportFolder;
        Shell shell  = HandlerUtil.getActiveShell(event);
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        File commandFile = new File(transportFolder + "\\Команда.txt"); //$NON-NLS-1$
        IProject project = null;
        if (commandFile.exists()
                && System.currentTimeMillis() - commandFile.lastModified() < 5000)
        {
            try
            {
                command = Global.readTextFromFile(commandFile);
                Files.delete(commandFile.toPath());
                ObjectMapper mapper = new ObjectMapper();
                JsonNode commandObject = mapper.readTree(command);
                command = commandObject.get("Команда").asText(); //$NON-NLS-1$
                long PID = commandObject.get("ИДПроцесса").asLong(); //$NON-NLS-1$
                session = IRApplication.getSession(PID);
                project = session.project;
            }
            catch (IOException e) { e.printStackTrace(); }
            newFile = new File(transportFolder + "\\НовыйТекст.txt"); //$NON-NLS-1$
            oldFile = new File(transportFolder + "\\СтарыйТекст.txt"); //$NON-NLS-1$
        }
        else
        {
            command = getClipboardText(shell);
            if (command == null || command.isBlank())
            {
                ToastNotification.show("Перейти к определению", "Буфер обмена пуст.", 4000);
                return null;
            }
            command = command.strip();
        }
        if (project==null)
            project = Global.getActiveEditorProject(false);
        if (project == null)
        {
            ToastNotification.show("Переход к определению", "Отсутствует активный проект");
            return null;
        }
        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(project);
        if (v8Project == null)
        {
            ToastNotification.show("Переход к определению", "Проект " + project.getName() + " не открыт");
            return null;
        }
        
        if (!jump(command, shell, page, project))
        {
            ToastNotification.show("Перейти к определению",
                "В проекте " + project.getName() + " не найдена ссылка:\n" + truncate(command, 120), 5000);
            return null;
        }

        if (session != null && command.contains("Макет.")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            IEditorPart activeEditor = page.getActiveEditor();
            if (activeEditor instanceof DtGranularEditor<?>)
            {
                DtGranularEditor<?> templateEditor = (DtGranularEditor<?>) activeEditor;
                DtGranularEditorEmbeddedEditorPage<?> dcsPage =
                    (DtGranularEditorEmbeddedEditorPage<?>) templateEditor
                        .findPage("editors.commontemplate.pages.dcs"); //$NON-NLS-1$
                if (dcsPage != null && dcsPage.getEmbeddedEditor() != null && newFile != null)
                {
                    try
                    {
                        String currentFilename = DataCompositionSchemaEditorHook.exportToFile(dcsPage);
                        boolean allowImport = Global.readTextFromFile(new File(currentFilename))
                            .compareTo(Global.readTextFromFile(oldFile)) == 0;
                        if (allowImport)
                            DataCompositionSchemaEditorHook.importFromFile(dcsPage, newFile);
                        else
                            notifyDenyReplaceObject(newFile, command);
                    }
                    catch (Exception e) { e.printStackTrace(); }
                }
                else
                {
                    MoxelEditor moxelEditor = MoxelEditorHook.findMoxelEditor(templateEditor);
                    if (moxelEditor != null)
                        MoxelEditorHook.importTabularDocumentFromIrClipboard(moxelEditor, shell);
                    ToastNotification.show("Редактор ИР",
                        "Объект получен из буфера обмена. Резервный файл смотри в приложении ИР"); //$NON-NLS-1$
                }
            }
        }
        if (newFile != null && (command.contains("Модуль(") || command.contains("Форма("))) {
            session.syncCodeEditorToIR(GetRef.getActiveBslEditor(page.getActivePart()));
            final IRSession finalSession = session;
            try {
                String currentTextLiteral = session.executeOnComThread(() ->
                    finalSession.selectTextLiteral()
                );
                boolean allowImport = Global.normalizeLineSeparators(currentTextLiteral).compareTo(Global.readTextFromFile(oldFile))==0;
                String newText = Global.readTextFromFile(newFile);
                if (allowImport) {
                    session.executeOnComThread(() ->
                        {
                            return finalSession.replaceSelectedText(newText);
                        });
                    session.syncCodeEditorFromIR(GetRef.getActiveBslEditor(page.getActivePart()));
                } else {
                    notifyDenyReplaceObject(newFile, command);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public static void notifyDenyReplaceObject(File newFile, String command)
    {
        String details = newFile != null
            ? "Временный файл: " + newFile //$NON-NLS-1$
            : command;
        ToastNotification.show("Редактор ИР",
            "Объект был изменён в EDT после начала редактирования в ИР. "
            + "Загрузка не выполнена. " + details, 10000); //$NON-NLS-1$
    }

    public static boolean jump(String raw, Shell shell, IWorkbenchPage page, IProject project)
    {
        if (raw == null) 
            return false;
        String ref = raw.strip();
        if (ref.isEmpty()) 
            return false;

        if (ref.startsWith("http://") || ref.startsWith("https://")) //$NON-NLS-1$ //$NON-NLS-2$
        { openUrl(ref); 
         return true;
        }

        if (ref.contains("{") && ref.contains("}")) //$NON-NLS-1$ //$NON-NLS-2$
            return handleModuleLineRefs(ref, shell, page, project);

        Matcher gitM = GIT_FILE_REF.matcher(ref);
        if (gitM.matches())
            return openGitFileRef(gitM.group(1), parseInt(gitM.group(2)), page, shell, project);

        if (ref.startsWith("БД.")) ref = ref.substring(3); //$NON-NLS-1$

        if (ref.startsWith("ПакетXDTO.")) //$NON-NLS-1$
            return openXdtoRef(ref, shell, page, project);

        if (ref.contains(",")) //$NON-NLS-1$
        {
            List<String> names = extractMdNamesFromTypeDescription(ref);
            if (!names.isEmpty())
            {
                if (names.size() == 1) 
                    return openByFullName(names.get(0), shell, page, project);
                return pickAndOpen(names, shell, page, project);
            }
        }

        String stripped = stripTypeSuffix(ref);
        if (stripped != null) 
            return openByFullName(stripped, shell, page, project);

        return openByFullName(ref, shell, page, project);
    }

    // =======================================================================
    // ССЫЛКИ СТРОК МОДУЛЕЙ
    // =======================================================================

    private static boolean handleModuleLineRefs(String text, Shell shell, IWorkbenchPage page, IProject project)
    {
        List<ModuleLineRef> refs = parseAllModuleLineRefs(text);
        if (refs.isEmpty()) 
            return false;

        ModuleLineRef chosen;
        if (refs.size() == 1)
        {
            chosen = refs.get(0);
        }
        else
        {
            String[] labels = refs.stream().map(ModuleLineRef::displayLabel).toArray(String[]::new);
            MessageDialog dlg = new MessageDialog(shell,
                "Выберите строку стека", null, //$NON-NLS-1$
                "Найдено несколько ссылок. Выберите строку для перехода:", //$NON-NLS-1$
                MessageDialog.QUESTION, labels, 0);
            int idx = dlg.open();
            if (idx < 0 || idx >= refs.size())
                return false;
            chosen = refs.get(idx);
        }
        return openModuleLineRef(chosen, page, shell, project);
    }

    /**
     * Открывает редактор модуля по ссылке строки.
     *
     * <h3>Приоритеты</h3>
     * <ol>
     *   <li>Git-путь ({@code r.file != null}) → файловый переход.</li>
     *   <li><b>URI-подход</b>: строим {@code platform:/resource/.../Module.bsl#/0}
     *       и вызываем {@link OpenHelper#openEditor(URI, ISelection)} — EDT открывает
     *       редактор объекта и <em>автоматически</em> активирует страницу модуля.</li>
     *   <li>Fallback: прямое открытие BSL-файла через {@link IDE#openEditor}.</li>
     * </ol>
     */
    private static boolean openModuleLineRef(ModuleLineRef r, IWorkbenchPage page, Shell shell, IProject project)
    {
        
        if (r.file != null)
            return openGitFileRef(r.file, r.line, page, shell, project);
        if (openModuleRefViaUri(r, page, project)) 
            return true;
        return false;
//        // Fallback: файловый путь без активации страницы
//        String bslPath = moduleToBslPath(r.modulePath, r.extension);
//        if (bslPath == null)
//        {
//            Global.log("GoToDefinition: не могу построить BSL-путь для " + r.modulePath); //$NON-NLS-1$
//            return false;
//        }
//        return openBslFileAt(bslPath, r.line, page, shell);
    }

    // =======================================================================
    // ОТКРЫТИЕ МОДУЛЯ ЧЕРЕЗ platform:-URI
    // =======================================================================

    /**
     * Открывает модуль BSL через URI вида
     * {@code platform:/resource/<Проект>/src/.../Module.bsl#/0}.
     *
     * <p>Этот подход аналогичен родному коду EDT:
     * {@code openHelper.openEditor(EcoreUtil.getURI(module), (ISelection)null)}.
     * EDT по URI находит нужный ресурс, открывает редактор объекта-владельца
     * и <b>сам активирует страницу модуля</b> — никакой ручной обработки
     * {@code FormEditor.setActivePage} или {@code moduleProperty} не требуется.
     *
     * <p>После открытия переходим к целевой строке:
     * <ul>
     *   <li><b>Расширенная ссылка</b> ({@code r.method != null}): ищем объявление
     *       метода в документе и добавляем смещение {@code r.offset} — переход
     *       корректен, даже если метод переместился по файлу.</li>
     *   <li><b>Стандартная ссылка</b>: переходим к абсолютному номеру строки {@code r.line}.</li>
     * </ul>
     */
    private static boolean openModuleRefViaUri(ModuleLineRef r, IWorkbenchPage page, IProject project)
    {
        if (r.modulePath == null) 
            return false;

        // 1. Путь к BSL-файлу (src/<Folder>/<Object>/ObjectModule.bsl и т.п.)
        String bslPath = moduleToBslPath(r.modulePath, r.extension);
        if (bslPath == null)
        {
            Global.log("GoToDefinition: moduleToBslPath вернул null для: " + r.modulePath); //$NON-NLS-1$
            return false;
        }

        // 2. Находим файл в воркспейсе
        IFile file = findFileInWorkspace(bslPath, page, project);
        if (file == null)
        {
            Global.log("GoToDefinition: BSL-файл не найден: " + bslPath); //$NON-NLS-1$
            return false;
        }

        // 3. Строим platform:-URI на корневой EObject модуля:
        //    platform:/resource/<Проект>/src/.../Module.bsl#/0
        //    Фрагмент "/0" адресует первый (и единственный) EObject ресурса.
        URI moduleUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true)
                           .appendFragment("/0"); //$NON-NLS-1$

        // 4. Открываем редактор — EDT активирует нужную страницу автоматически
        IEditorPart editorPart = new OpenHelper().openEditor(moduleUri, (ISelection) null);
        if (editorPart == null)
        {
            Global.log("GoToDefinition: openEditor вернул null для URI: " + moduleUri); //$NON-NLS-1$
            return false;
        }

        // 5. Извлекаем XtextEditor и переходим к строке
        XtextEditor xtextEditor = BslHandlerUtil.extractXtextEditor(editorPart);
        if (xtextEditor == null)
        {
            Global.log("GoToDefinition: extractXtextEditor вернул null"); //$NON-NLS-1$
            return false; 
        }

        IXtextDocument document = (IXtextDocument) xtextEditor.getDocument();
        int targetLine0 = resolveTargetLine(r, document);

        try
        {
            int offset = document.getLineOffset(Math.max(0, targetLine0));
            xtextEditor.selectAndReveal(offset + r.column, 0);
            return true; 
        }
        catch (BadLocationException e)
        {
            Global.log("GoToDefinition: selectAndReveal(" + targetLine0 + "): " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return false; 
        }
    }

    /**
     * Определяет 0-based номер целевой строки.
     *
     * <p>Для расширенной ссылки ({@code r.method != null}) ищет объявление метода
     * и добавляет смещение {@code r.offset}. Это позволяет корректно перейти
     * к строке даже если метод был перемещён по файлу со времени создания ссылки.
     * Если метод не найден — использует абсолютный {@code r.line}.
     */
    private static int resolveTargetLine(ModuleLineRef r, IXtextDocument document)
    {
        if (r.method != null && !r.method.isEmpty())
        {
            int methodLine0 = findMethodLine(document, r.method);
            if (methodLine0 >= 0) 
                return methodLine0 + r.offset;
        }
        return Math.max(0, r.line - 1);
    }

    /**
     * Ищет 0-based номер строки объявления метода {@code methodName}.
     * Поддерживает «Процедура»/«Функция»/«Procedure»/«Function» и опциональный «Асинх».
     *
     * @return 0-based номер строки или {@code -1} если метод не найден
     */
    private static int findMethodLine(IXtextDocument document, String methodName)
    {
        Pattern p = Pattern.compile(
            "^\\s*(?:Асинх\\s+)?(?:Процедура|Функция|Procedure|Function)\\s+" //$NON-NLS-1$
            + Pattern.quote(methodName) + "\\s*[\\(\\s]", //$NON-NLS-1$
            Pattern.UNICODE_CASE);

        String text  = document.get();
        int    pos   = 0;
        int    line  = 0;
        while (pos <= text.length())
        {
            int eol = text.indexOf('\n', pos);
            if (eol < 0) eol = text.length();
            if (p.matcher(text.substring(pos, eol)).find()) 
                return line;
            pos = eol + 1;
            line++;
        }
        return -1;
    }

    // =======================================================================
    // GIT-ССЫЛКА
    // =======================================================================

    private static boolean openGitFileRef(String filePath, int line, IWorkbenchPage page, Shell shell, IProject project)
    {
        IFile file = findFileInWorkspace(filePath, page, project);
        if (file == null && !filePath.startsWith("src/")) //$NON-NLS-1$
            file = findFileInWorkspace("src/" + filePath, page, project); //$NON-NLS-1$
        if (file == null)
        {
            Global.log("GoToDefinition: файл не найден: " + filePath); //$NON-NLS-1$
            return false;
        }
        return openFileAtLine(file, line, page, shell);
    }

    // =======================================================================
    // XDTO
    // =======================================================================

    private static boolean openXdtoRef(String ref, Shell shell, IWorkbenchPage page, IProject project)
    {
        String[] parts = ref.split("\\.", 3); //$NON-NLS-1$
        if (parts.length < 2) 
            return false;
        return openMdObjectByFullName("ПакетXDTO." + parts[1], shell, page, project); //$NON-NLS-1$
    }

    // =======================================================================
    // ПОЛНОЕ ИМЯ МД
    // =======================================================================

    public static boolean openByFullName(String fullName, Shell shell, IWorkbenchPage page, IProject project)
    {
        if (fullName == null || fullName.isBlank()) 
            return false;
        fullName = fullName.strip();

        String extension = null;
        int spaceIdx = fullName.indexOf(' ');
        if (spaceIdx > 0 && !fullName.substring(0, spaceIdx).contains(".")) //$NON-NLS-1$
        {
            extension = fullName.substring(0, spaceIdx);
            fullName  = fullName.substring(spaceIdx + 1);
        }
        if (isModuleSuffixPath(fullName))
        {
            String bslPath = moduleToBslPath(fullName, extension);
            if (bslPath != null)
                return openBslFileAt(bslPath, 0, page, shell, project);
        }
        return openMdObjectByFullName(fullName, shell, page, project);
    }

    // =======================================================================
    // EDT: ОТКРЫТИЕ ОБЪЕКТА МД
    // =======================================================================

    private static boolean openMdObjectByFullName(String fullName, Shell shell, IWorkbenchPage page, IProject project)
    {
        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IProject activeProject = Global.getActiveProject(page, true);
        if (activeProject == null)
        {
            return false;
        }
        IV8Project v8Project = projectManager.getProject(activeProject);
        if (v8Project == null)
        {
            return false;
        }
        EObject eObject = resolveEObjectByQualifiedName(fullName, v8Project);
        if (eObject instanceof MdObject)
        {
            new OpenHelper().openEditor((MdObject) eObject, null);
            return true;
        }
        String mdoPath = mdNameToMdoPath(fullName);
        if (mdoPath == null)
        {
            Global.log("GoToDefinition: не могу построить .mdo-путь для: " + fullName); //$NON-NLS-1$
            return false;
        }
        IFile mdoFile = findFileInWorkspace(mdoPath, page, project);
        if (mdoFile == null)
        {
            Global.log("GoToDefinition: .mdo не найден: " + mdoPath); //$NON-NLS-1$
            return false;
        }
        eObject = resolveEObjectViaResourceSet(mdoFile, v8Project);
        if (eObject instanceof MdObject)
        {
            new OpenHelper().openEditor((MdObject) eObject, null);
            return true;
        }
        Global.log("GoToDefinition: EObject не получен, открываем .mdo напрямую"); //$NON-NLS-1$
        try { 
            return IDE.openEditor(page, mdoFile, true) != null; }
        catch (PartInitException e)
        {
            Global.log("GoToDefinition: IDE.openEditor(.mdo): " + e); //$NON-NLS-1$
            return false;
        }
    }

    public static EObject resolveEObjectViaResourceSet(IFile file, IV8Project v8Project)
    {
        URI fileUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        IResourceServiceProvider rsp =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(fileUri);
        IQualifiedNameFilePathConverter conv = rsp.get(IQualifiedNameFilePathConverter.class);
        QualifiedName fqn = conv.getFqn(file);
        if (fqn == null)
        {
            Global.log("GoToDefinition: getFqn вернул null для " + file.getFullPath()); //$NON-NLS-1$
            return null;
        }
        return resolveEObjectByQualifiedName(fqn.toString(), v8Project);
    }

    public static EObject resolveEObjectByQualifiedName(String fqn, IV8Project v8Project)
    {
        IBmModelManager modelManager =
            (IBmModelManager)Global.getServiceByClass(IBmModelManager.class);
        BmPlatform platform = modelManager.getBmPlatform();
        IBmPlatformTransaction transaction = platform.beginReadOnlyTransaction(true);
        IBmNamespace ns = modelManager.getBmNamespace(v8Project.getProject());
        try
        {
            return (EObject)transaction.getTopObjectByFqn(ns, fqn);
        }
        catch (Exception e)
        {
            return null;
        }
        finally
        {
            transaction.commit();
        }
    }

    // =======================================================================
    // ФАЙЛОВЫЕ ОПЕРАЦИИ
    // =======================================================================

    private static boolean openBslFileAt(String bslPath, int line, IWorkbenchPage page, Shell shell, IProject project)
    {
        IFile file = findFileInWorkspace(bslPath, page, project);
        if (file == null)
        {
            Global.log("GoToDefinition: BSL не найден: " + bslPath);
            return false;
        } //$NON-NLS-1$
        return openFileAtLine(file, line, page, shell);
    }

    private static boolean openFileAtLine(IFile file, int line, IWorkbenchPage page, Shell shell)
    {
        try
        {
            IEditorPart editor = IDE.openEditor(page, file, true);
            if (editor == null)
                return false;
            if (line > 0)
                revealLine(editor, line);
            return true;
        }
        catch (PartInitException e)
        {
            Global.log("GoToDefinition: openFileAtLine: " + e);
            return false;
        } //$NON-NLS-1$
    }

    private static void revealLine(IEditorPart editor, int lineNumber)
    {
        if (lineNumber <= 0 || !(editor instanceof ITextEditor)) 
            return;
        ITextEditor te  = (ITextEditor) editor;
        IDocument   doc = te.getDocumentProvider().getDocument(te.getEditorInput());
        if (doc == null) return;
        try { te.selectAndReveal(doc.getLineOffset(Math.max(0, lineNumber - 1)), 0); }
        catch (BadLocationException ignored) {}
    }

    private static IFile findFileInWorkspace(String relPath, IWorkbenchPage page, IProject project)
    {
        String path = relPath.replace('\\', '/');
        if (project==null)
        {
            project = Global.getActiveProject(page, true);
        }
        if (project != null) {
            IFile f = findInProject(project, path);
            if (f != null)
                return f; 
        }
        // Не будетм перебирать все проекты, если актвиного нет
//        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
//        {
//            if (!p.isOpen() || p.equals(active)) continue;
//            IFile f = findInProject(p, path);
//            if (f != null) return f;
//        }
        return null;
    }

    private static IFile findInProject(IProject project, String path)
    {
        IFile f = project.getFile(path);
        return f.exists() ? f : null;
    }

    // =======================================================================
    // КОНВЕРТАЦИЯ ИМЁН МОДУЛЕЙ → ПУТИ BSL
    // =======================================================================

    /**
     * Преобразует русский путь модуля (из ссылки строки) в project-relative путь BSL-файла.
     *
     * <pre>
     *   "ОбщийМодуль.МойМодуль"                             → "src/CommonModules/МойМодуль/Module.bsl"
     *   "ОбщаяФорма.Форма1.Форма"                           → "src/CommonForms/Форма1/Module.bsl"
     *   "Справочник.Валюты.МодульОбъекта"                   → "src/Catalogs/Валюты/ObjectModule.bsl"
     *   "Обработка.ирКод.Форма.Форма.Форма"                 → "src/DataProcessors/ирКод/Forms/Форма/Module.bsl"
     *   "ИнструментыTormozit Обработка.ирКод.Форма.Форма.Форма" → "src/ext/ИнструментыTormozit/DataProcessors/ирКод/Forms/Форма/Module.bsl"
     * </pre>
     */
    static String moduleToBslPath(String modulePath, String extension)
    {
        if (modulePath == null || modulePath.isBlank())
            return null;

        String[] p = modulePath.split("\\.");
        if (p.length < 3)
            return null;

        String rootFolder = MdTypeMapping.anyToFolder(p[0]);
        if (rootFolder == null)
            return null;

        StringBuilder path = new StringBuilder("src/");

        if (extension != null && !extension.isBlank())
        {
            path.append("ext/")
                .append(extension)
                .append('/');
        }

        path.append(rootFolder)
            .append('/')
            .append(p[1]);

        int last = p.length - 1;
        String moduleTypeRu = p[last];

        // контейнеры: Команда и Форма
        for (int i = 2; i < last - 1; i += 2)
        {
            String folder = MdTypeMapping.anyToFolder(p[i]);
            if (folder == null)
                return null;

            path.append('/')
                .append(folder)
                .append('/')
                .append(p[i + 1]);
        }

        if ("Форма".equals(moduleTypeRu))
        {
            return path.append("/Module.bsl").toString();
        }

        path.append('/')
            .append(MdTypeMapping.ruToEnSingRequired(moduleTypeRu))
            .append(".bsl");

        return path.toString();
    }

    private static String mdNameToMdoPath(String fullName)
    {
        String[] parts = fullName.split("\\.", 3); //$NON-NLS-1$
        if (parts.length < 2) 
            return null;
        String folder = MdTypeMapping.anyToFolder(parts[0]);
        if (folder == null) 
            return null;
        String objectName = parts[1];
        return "src/" + folder + "/" + objectName + "/" + objectName + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // =======================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // =======================================================================

    private static String stripTypeSuffix(String ref)
    {
        int dot = ref.indexOf('.');
        if (dot < 0) 
            return null;
        String base = TYPE_SUFFIX_MAP.get(ref.substring(0, dot));
        return base != null ? base + ref.substring(dot) : null;
    }

    private static List<String> extractMdNamesFromTypeDescription(String types)
    {
        List<String> result = new ArrayList<>();
        for (String part : TYPE_DESCRIPTION_SPLITTER.split(types))
        {
            String t = part.strip();
            if (t.isEmpty()) 
                continue;
            String stripped = stripTypeSuffix(t);
            if (stripped != null)    
                result.add(stripped);
            else if (t.contains(".")) 
                result.add(t); //$NON-NLS-1$
        }
        return result;
    }

    private static boolean pickAndOpen(List<String> names, Shell shell, IWorkbenchPage page, IProject project)
    {
        MessageDialog dlg = new MessageDialog(shell,
            "Выберите объект", null, //$NON-NLS-1$
            "Найдено несколько объектов. Выберите для перехода:", //$NON-NLS-1$
            MessageDialog.QUESTION, names.toArray(new String[0]), 0);
        int idx = dlg.open();
        if (idx < 0 || idx >= names.size()) 
            return false;
        return openByFullName(names.get(idx), shell, page, project);
    }

    private static boolean isModuleSuffixPath(String name)
    {
        String lastSeg = name.substring(name.lastIndexOf('.') + 1);
        return lastSeg.startsWith("Module");
    }

    private static int indexOfFormPart(String fullName)
    {
        for (String marker : new String[]{ ".Форма.", ".Forms.", ".ЭлементыФормы.", ".Элементы." }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            int idx = fullName.indexOf(marker);
            if (idx >= 0) 
                return idx;
        }
        return -1;
    }

    // =======================================================================
    // ПАРСИНГ ССЫЛОК СТРОК МОДУЛЕЙ
    // =======================================================================

    private static List<ModuleLineRef> parseAllModuleLineRefs(String text)
    {
        List<ModuleLineRef> result = new ArrayList<>();
        Matcher m = MODULE_LINE_REF.matcher(text);
        while (m.find())
        {
            ModuleLineRef r = new ModuleLineRef();
            r.extension = m.group(1);
            r.modulePath = m.group(2);
            r.line = parseInt(m.group(3));
            r.column = parseInt(m.group(4));
            r.method = m.group(5);
            r.offset = parseInt(m.group(6));
            if (r.modulePath.contains("/") || r.modulePath.contains("\\")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                r.file = r.modulePath;
                r.modulePath = null;
            }
            result.add(r);
        }
        return result;
    }

    private static final class ModuleLineRef
    {
        String extension;
        String modulePath;
        String file;
        int line;
        int column;
        String method;
        int offset;

        String displayLabel()
        {
            String base = modulePath != null ? modulePath : file;
            String s = (base != null ? base : "") + " (" + line + "," + column + ")";
            if (method != null)
                s += " : " + method; //$NON-NLS-1$
            if (extension != null)
                s += " [" + extension + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            return s;
        }
    }

    // =======================================================================
    // УТИЛИТЫ
    // =======================================================================

    private static void openUrl(String url)
    {
        try
        {
            org.eclipse.swt.program.Program.launch(url);
        }
        catch (Exception e)
        {
            Global.log("GoToDefinition: openUrl: " + e); //$NON-NLS-1$
        }
    }

    private static String getClipboardText(Shell shell)
    {
        Clipboard cb = new Clipboard(shell.getDisplay());
        try
        {
            return (String)cb.getContents(TextTransfer.getInstance());
        }
        finally
        {
            cb.dispose();
        }
    }

    private static int parseInt(String s)
    {
        if (s == null)
            return 0;
        try
        {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private static String truncate(String s, int max)
    {
        return s.length() <= max ? s : s.substring(0, max) + "\u2026"; //$NON-NLS-1$
    }
}
