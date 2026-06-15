package tormozit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.eclipse.jface.text.Document;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
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
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.sheet.CellsSelection;
import com._1c.g5.v8.dt.moxel.sheet.Selection;
import com._1c.g5.v8.dt.moxel.sheet.SheetAccessor;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelControl;
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

    // Формат ссылки — порт regex {@code ирОбщий.ШаблонСсылкиСтрокиМодуляЛкс} + git alternate
    // из {@code ирОбщий.СтруктураСсылкиСтрокиМодуляЛкс}; см. https://fastcode.im/Templates/8426
    private static final Pattern MODULE_LINE_REF = Pattern.compile(
        "\\{"
        + "([a-zа-яёA-Za-z0-9_ ]+ )?"
        + "(([a-zа-яёA-Za-z0-9_]+::.+?::)?(?:[a-zа-яёA-Za-z0-9_]+\\.)*[a-zа-яёA-Za-z0-9_]+)"
        + "\\((\\d+)(?:,(\\d+))?"
        + "(?:\\:?([a-zа-яёA-Za-z0-9_<>\\.]*)(?:,\\s*(-?\\d+))?)?"
        + "(?:!([a-zа-яёA-Za-z0-9_\\.:\\\\]+))?"
        + "\\)\\}"
        + "|"
        + "((?:[a-zа-яёA-Za-z0-9_\\.]+\\/[a-zа-яёA-Za-z0-9_\\.]+)+):(\\d+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
        Global.log(this.getClass().getName() + ".execute");
        IRSession session = null;
        File newFile = null, oldFile = null;
        String command = "";
        boolean withFile = false;
        String transportFolder = IRApplication.transportFolder;
        Shell shell  = HandlerUtil.getActiveShell(event);
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
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
                withFile = commandObject.get("ЕстьФайл").asBoolean(); //$NON-NLS-1$
                session = IRApplication.getSession(PID);
                if (session != null)
                    project = session.project;
            }
            catch (IOException e) { Global.logError("GoToDefinition", "read transport command", e); } //$NON-NLS-1$ //$NON-NLS-2$
            if (withFile)
            {
                newFile = new File(transportFolder + "\\НовыйТекст.txt"); //$NON-NLS-1$
                oldFile = new File(transportFolder + "\\СтарыйТекст.txt"); //$NON-NLS-1$
            }
        }
        else if (event.getCommand().getId().equals("tormozit.JumpFromClipboard"))
        {
            command = getClipboardText(shell);
            if (command == null || command.isBlank())
            {
                ToastNotification.show("Перейти к определению", "Буфер обмена пуст.", 4000);
                return null;
            }
            command = command.strip();
        }
        else
        {
            command = getTextFromActiveEditor(page);
            if (command == null || command.isBlank())
            {
                ToastNotification.show("Перейти к определению",
                    "Не удалось получить текст из активного редактора.", 4000);
                return null;
            }
            command = command.strip();
        }
        if (project == null)
            project = Global.getActiveProject(part, false);
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
            if (!consumeJumpCancelled())
            {
                ToastNotification.show("Перейти к определению",
                    "В проекте " + project.getName() + " не найдена ссылка:\n" + truncate(command, 120), 5000);
            }
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
                    catch (Exception e) { Global.logError("GoToDefinition", "import DCS from file", e); } //$NON-NLS-1$ //$NON-NLS-2$
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
        if (session != null && newFile != null && (command.contains("Модуль(") || command.contains("Форма("))) {
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
                Global.logError("GoToDefinition", "sync from IR", e); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static boolean jumpCancelled;

    /** {@code true}, если последний {@link #jump} прерван отменой диалога выбора строки стека. */
    static boolean consumeJumpCancelled()
    {
        boolean cancelled = jumpCancelled;
        jumpCancelled = false;
        return cancelled;
    }

    public static boolean jump(String raw, Shell shell, IWorkbenchPage page, IProject project)
    {
        jumpCancelled = false;
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
            List<ModuleLinePickDialog.Row> rows = new ArrayList<>();
            for (ModuleLineRef r : refs)
                rows.add(toPickRow(r, page, project));
            ModuleLinePickDialog dlg = new ModuleLinePickDialog(shell, rows);
            if (dlg.open() != Window.OK || !dlg.wasConfirmed())
            {
                jumpCancelled = true;
                return false;
            }
            int idx = dlg.getSelectedIndex();
            if (idx < 0 || idx >= refs.size())
                return false;
            chosen = refs.get(idx);
        }
        return openModuleLineRef(chosen, page, shell, project);
    }

    private static ModuleLinePickDialog.Row toPickRow(ModuleLineRef r, IWorkbenchPage page, IProject project)
    {
        return new ModuleLinePickDialog.Row(
            moduleLabelForRef(r),
            resolveMethodName(r, page, project),
            r.line,
            r.lineText != null ? r.lineText : ""); //$NON-NLS-1$
    }

    private static String moduleLabelForRef(ModuleLineRef r)
    {
        if (r.moduleWithExtension != null)
            return r.moduleWithExtension;
        if (r.modulePath != null)
            return r.modulePath;
        return r.file != null ? r.file : ""; //$NON-NLS-1$
    }

    private static String resolveMethodName(ModuleLineRef r, IWorkbenchPage page, IProject project)
    {
        if (r.method != null && !r.method.isEmpty())
        {
            if (r.methodParam != null && !r.methodParam.isEmpty())
                return r.method + "." + r.methodParam; //$NON-NLS-1$
            return r.method;
        }

        IFile file = findFileForModuleLineRef(r, page, project);
        if (file == null)
        {
            if (Global.isLogEnabled())
                Global.log("GoToDefinition: BSL-файл не найден для " + moduleLabelForRef(r)); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }

        try (InputStream is = file.getContents())
        {
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Document doc = new Document(text);
            String name = GetRef.findEnclosingMethodName(doc, r.line);
            return name != null ? name : ""; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            if (Global.isLogEnabled())
                Global.log("GoToDefinition: не удалось прочитать " + file.getFullPath() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return ""; //$NON-NLS-1$
        }
    }

    private static IFile findFileForModuleLineRef(ModuleLineRef r, IWorkbenchPage page, IProject project)
    {
        if (r.file != null)
        {
            IFile file = findFileInWorkspace(r.file, page, project);
            if (file == null && !r.file.startsWith("src/")) //$NON-NLS-1$
                file = findFileInWorkspace("src/" + r.file, page, project); //$NON-NLS-1$
            return file;
        }
        if (r.modulePath == null)
            return null;
        String bslPath = moduleToBslPath(r.modulePath, r.extension);
        return bslPath != null ? findFileInWorkspace(bslPath, page, project) : null;
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
        if (project == null)
            return false;
        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(project);
        if (v8Project == null)
        {
            return false;
        }
        EObject eObject = resolveEObjectByQualifiedName(fullName, v8Project);
        if (eObject instanceof MdObject
                && openMdObjectViaOpenHelper((MdObject) eObject, fullName, page))
            return true;

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
        if (eObject instanceof MdObject
                && openMdObjectViaOpenHelper((MdObject) eObject, fullName, page))
            return true;

        Global.log("GoToDefinition: EObject не получен, открываем .mdo напрямую"); //$NON-NLS-1$
        try
        {
            return IDE.openEditor(page, mdoFile, true) != null;
        }
        catch (PartInitException e)
        {
            Global.log("GoToDefinition: IDE.openEditor(.mdo): " + e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Открывает объект МД через {@link OpenHelper}.
     * Из редактора табличного документа (MOXEL) {@code openEditor} может бросить NPE
     * при попытке переоткрыть текущий {@link DtGranularEditor} через AEF-страницу
     * с неинициализированной сценой — в этом случае возвращаем {@code false},
     * чтобы сработал fallback на {@code IDE.openEditor(.mdo)}.
     */
    private static boolean openMdObjectViaOpenHelper(MdObject mdObject, String fullName, IWorkbenchPage page)
    {
        if (page == null || mdObject == null)
            return false;
        if (isSameGranularEditorTarget(page, fullName))
            return true;
        try
        {
            IEditorPart editor = new OpenHelper(page).openEditor(mdObject, null);
            return editor != null;
        }
        catch (RuntimeException e)
        {
            Global.log("GoToDefinition: OpenHelper.openEditor: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /** Целевой объект уже открыт в активном {@link DtGranularEditor}. */
    private static boolean isSameGranularEditorTarget(IWorkbenchPage page, String fullName)
    {
        if (fullName == null || fullName.isBlank())
            return false;
        IEditorPart active = page.getActiveEditor();
        if (!(active instanceof DtGranularEditor<?>))
            return false;
        String currentRef = GetRef.getRefFromEditor(active);
        if (currentRef == null || currentRef.isBlank())
            return false;
        String targetFqn = MdTypeMapping.anyFullNameToBmFqn(fullName.strip());
        String currentFqn = MdTypeMapping.anyFullNameToBmFqn(currentRef.strip());
        return targetFqn != null && targetFqn.equalsIgnoreCase(currentFqn);
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

    public static EObject resolveEObjectByQualifiedName(String fullName, IV8Project v8Project)
    {
        String fqn = MdTypeMapping.anyFullNameToBmFqn(fullName);
        if (fqn == null) return null;
        IBmModelManager modelManager =
            (IBmModelManager)Global.getServiceByClass(IBmModelManager.class);
        BmPlatform platform = modelManager.getBmPlatform();
        IBmPlatformTransaction transaction = platform.beginReadOnlyTransaction(true);
        IBmNamespace ns = modelManager.getBmNamespace(v8Project.getProject());
        try
        {
            // getTopObjectByFqn принимает только FQN top-level объекта (первые два сегмента).
            // Если fullName содержит дополнительные сегменты (напр. Справочник.Валюты.Макет.Классификатор),
            // сначала получаем родительский top-object, затем обходим EMF-дерево вниз.
            String[] fqnParts = fqn.split("\\.", -1); //$NON-NLS-1$
            String topFqn = fqnParts[0] + "." + fqnParts[1]; //$NON-NLS-1$
            EObject eObject = (EObject) transaction.getTopObjectByFqn(ns, topFqn);
            if (eObject == null || fqnParts.length <= 2)
                return eObject;
            // Спускаемся по дочерним объектам по парам (тип, имя)
            for (int i = 2; i + 1 < fqnParts.length && eObject != null; i += 2)
            {
                String childType = fqnParts[i];
                String childName = fqnParts[i + 1];
                eObject = findChildByName(eObject, childType, childName);
            }
            return eObject;
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

    /**
     * Ищет дочерний EMF-объект в коллекции, соответствующей категории (Реквизит → attributes).
     */
    private static EObject findChildByName(EObject parent, String categoryType, String name)
    {
        if (parent == null || categoryType == null || name == null)
            return null;
        String featureName = MdTypeMapping.subObjectTypeToEmfFeature(categoryType);
        if (featureName == null)
            return null;
        org.eclipse.emf.ecore.EStructuralFeature feature =
            parent.eClass().getEStructuralFeature(featureName);
        if (feature == null)
            return null;
        Object val = parent.eGet(feature);
        if (feature.isMany())
        {
            if (!(val instanceof java.util.List<?>))
                return null;
            for (Object item : (java.util.List<?>) val)
            {
                if (item instanceof EObject)
                {
                    EObject child = (EObject) item;
                    String childName = getEObjectName(child);
                    if (name.equalsIgnoreCase(childName))
                        return child;
                }
            }
        }
        else if (val instanceof EObject)
        {
            EObject child = (EObject) val;
            String childName = getEObjectName(child);
            if (name.equalsIgnoreCase(childName))
                return child;
        }
        return null;
    }

    /**
     * Получает имя EObject через структурный признак "name".
     */
    private static String getEObjectName(EObject obj)
    {
        org.eclipse.emf.ecore.EStructuralFeature nameFeature = obj.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (nameFeature == null) return null;
        Object val = obj.eGet(nameFeature);
        return val instanceof String ? (String) val : null;
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
        if (project == null)
            return null;
        String path = relPath.replace('\\', '/');
        IFile f = findInProject(project, path);
        if (f != null)
            return f;
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
        MdObjectPickDialog dlg = new MdObjectPickDialog(shell, names);
        if (dlg.open() != Window.OK)
            return false;
        String chosen = dlg.getSelectedFullName();
        if (chosen == null)
            return false;
        return openByFullName(chosen, shell, page, project);
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
    // ПАРСИНГ ССЫЛОК СТРОК МОДУЛЕЙ (порт ирОбщий.СтруктураСсылкиСтрокиМодуляЛкс)
    // =======================================================================

    /** Все вхождения {@link #MODULE_LINE_REF} в тексте (стек, буфер). */
    private static List<ModuleLineRef> parseAllModuleLineRefs(String text)
    {
        List<ModuleLineRef> result = new ArrayList<>();
        Matcher m = MODULE_LINE_REF.matcher(text);
        while (m.find())
        {
            ModuleLineRef r = parseModuleLineRef(m, text);
            if (r != null)
                result.add(r);
        }
        return result;
    }

    /**
     * Разбор одного совпадения — порт {@code ирОбщий.СтруктураСсылкиСтрокиМодуляЛкс}
     * ({@code НормализоватьИмяМодуля = Истина}); только структура, без валидации.
     */
    private static ModuleLineRef parseModuleLineRef(Matcher m, String fullText)
    {
        ModuleLineRef r = new ModuleLineRef();
        r.refText = m.group();
        r.lineText = extractStackLineCode(fullText, m.end());

        String moduleRaw = m.group(2);
        if (moduleRaw != null)
            return parseNativeModuleLineRef(r, m, moduleRaw);

        String gitFile = m.group(9);
        if (gitFile != null)
        {
            r.file = gitFile;
            r.line = parseInt(m.group(10));
            return r;
        }
        return null;
    }

    /** Исходный код строки из стека — текст после {@code {...}:} до конца строки. */
    private static String extractStackLineCode(String fullText, int afterRefEnd)
    {
        if (fullText == null || afterRefEnd < 0 || afterRefEnd >= fullText.length())
            return ""; //$NON-NLS-1$

        int pos = afterRefEnd;
        if (fullText.charAt(pos) == ':')
            pos++;

        while (pos < fullText.length())
        {
            char ch = fullText.charAt(pos);
            if (ch == '\n' || ch == '\r')
                break;
            if (ch != ' ' && ch != '\t')
                break;
            pos++;
        }

        int lineEnd = fullText.indexOf('\n', pos);
        if (lineEnd < 0)
            lineEnd = fullText.length();
        return fullText.substring(pos, lineEnd).strip();
    }

    /** Нативная ветка {@code ирОбщий.СтруктураСсылкиСтрокиМодуляЛкс} (формат {@code {...}}). */
    private static ModuleLineRef parseNativeModuleLineRef(ModuleLineRef r, Matcher m, String moduleRaw)
    {
        r.extension = trimToNull(m.group(1));
        r.module = moduleRaw;

        String[] fragments1 = moduleRaw.split("\\.", -1); //$NON-NLS-1$
        String[] fragments2 = moduleRaw.split("::", -1); //$NON-NLS-1$

        if (fragments2.length > 2)
        {
            r.file = fragments2[1];
            String fileExt = fileExtension(r.file);
            r.objectType = ".erf".equalsIgnoreCase(fileExt) //$NON-NLS-1$
                ? "ВнешнийОтчет" : "ВнешняяОбработка"; //$NON-NLS-1$ //$NON-NLS-2$
            r.internal = r.objectType + "." + fragments2[2]; //$NON-NLS-1$
            List<String> rebuilt = new ArrayList<>(Arrays.asList(fragments1));
            if (!rebuilt.isEmpty())
                rebuilt.set(0, r.file);
            else
                rebuilt.add(r.file);
            rebuilt.add(0, r.objectType);
            fragments1 = rebuilt.toArray(new String[0]);
        }
        else if (fragments1.length > 0)
        {
            r.objectType = fragments1[0];
        }

        if (fragments1.length > 1)
            r.object = fragments1[1];

        if (fragments1.length > 3 && "Форма".equals(fragments1[2])) //$NON-NLS-1$
            r.form = fragments1[3];
        else if (fragments1.length > 1 && "ОбщаяФорма".equals(fragments1[0])) //$NON-NLS-1$
            r.form = fragments1[1];

        if (r.form != null && !r.form.isEmpty())
            r.module = moduleRaw + ".Модуль"; //$NON-NLS-1$

        r.moduleWithExtension = r.module;
        if (r.extension != null)
            r.moduleWithExtension = r.extension + " " + r.moduleWithExtension; //$NON-NLS-1$

        r.line = parseInt(m.group(4));
        if (r.line == 0)
            r.line = 1;
        r.column = m.group(5) != null ? parseInt(m.group(5)) : 1;

        String methodRaw = m.group(6);
        r.method = methodRaw;
        r.offset = m.group(7) != null ? parseInt(m.group(7)) : 0;
        r.command = m.group(8);

        if (methodRaw != null)
        {
            int dot = methodRaw.indexOf('.');
            if (dot >= 0)
            {
                r.method = methodRaw.substring(0, dot);
                r.methodParam = methodRaw.substring(dot + 1);
            }
        }

        String[] moduleParts = r.module.split("\\.", -1); //$NON-NLS-1$
        if (moduleParts.length > 0)
            r.moduleType = moduleParts[moduleParts.length - 1];

        r.modulePath = r.module;
        return r;
    }

    private static String trimToNull(String s)
    {
        if (s == null)
            return null;
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    private static String fileExtension(String path)
    {
        if (path == null)
            return ""; //$NON-NLS-1$
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash && dot >= 0 ? path.substring(dot) : ""; //$NON-NLS-1$
    }

    private static final class ModuleLineRef
    {
        /** Поля структуры {@code ирОбщий.СтруктураСсылкиСтрокиМодуляЛкс}. */
        String module;
        String moduleWithExtension;
        String internal;
        String file;
        String objectType;
        String moduleType;
        String extension;
        String object;
        String form;
        int line;
        int column;
        String method;
        String methodParam;
        int offset;
        String lineText;
        String command;
        String refText;

        /** Legacy-поле для {@link #openModuleLineRef}. */
        String modulePath;

        String displayLabel()
        {
            String base = moduleWithExtension != null ? moduleWithExtension
                : (modulePath != null ? modulePath : file);
            String s = (base != null ? base : "") + " (" + line + "," + column + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (method != null)
            {
                s += " : " + method; //$NON-NLS-1$
                if (methodParam != null)
                    s += "." + methodParam; //$NON-NLS-1$
            }
            if (file != null && modulePath == null)
                s = file + " (" + line + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            return s;
        }
    }

    // =======================================================================
    // ТЕКСТ ИЗ АКТИВНОГО РЕДАКТОРА (основная команда без аргументов)
    // =======================================================================

    /**
     * Пытается получить целевой текст из активного редактора по следующей цепочке
     * приоритетов:
     *
     * <ol>
     *   <li><b>BSL-редактор — выделение</b>: если пользователь выделил текст
     *       в BSL-редакторе, возвращается выделенный фрагмент.</li>
     *   <li><b>BSL-редактор — слово/выражение под курсором</b>: если курсор стоит
     *       внутри слова, извлекается максимально длинная цепочка идентификаторов
     *       через точку (например {@code Справочники.Валюты}).</li>
     *   <li><b>Активный SWT-виджет — текстовое поле или ячейка</b>: если фокус
     *       стоит в {@link org.eclipse.swt.widgets.Text},
     *       {@link org.eclipse.swt.widgets.Combo},
     *       {@link org.eclipse.swt.custom.StyledText} или
     *       {@link MoxelControl}, возвращается выделенный текст,
     *       а при его отсутствии — всё содержимое поля/ячейки.</li>
     * </ol>
     *
     * @return текст для перехода или {@code null} / пустая строка если ничего не найдено
     */
    private static String getTextFromActiveEditor(IWorkbenchPage page)
    {
        if (page == null) return null;

        // 1. BSL-редактор
        IEditorPart editor = page.getActiveEditor();
        BslXtextEditor bslEditor = GetRef.getActiveBslEditor(
                editor != null ? editor : page.getActivePart());
        if (bslEditor != null)
        {
            String text = getTextFromBslEditor(bslEditor);
            if (text != null && !text.isBlank())
                return text.substring(0, 1000);
        }

        // 2. Активный SWT-виджет (ячейка таблицы, поле ввода формы МД и т. п.)
        return getTextFromFocusedWidget();
    }

    /**
     * Извлекает текст из BSL-редактора.
     * Если есть непустое выделение — возвращает его.
     * Иначе — цепочку идентификаторов через точку вокруг позиции курсора.
     */
    private static String getTextFromBslEditor(BslXtextEditor editor)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null) return null;
        IDocument doc = viewer.getDocument();
        if (doc == null) return null;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection)) return null;
        ITextSelection textSel = (ITextSelection) sel;

        // 1a. Есть непустое выделение — берём его напрямую
        String selected = textSel.getText();
        if (selected != null && !selected.isBlank())
            return selected.strip();

        // 1b. Слово/идентификатор под курсором
        try
        {
            return extractQualifiedNameAt(doc, textSel.getOffset());
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    /**
     * Извлекает максимально длинный составной идентификатор (цепочку через точку)
     * вокруг позиции {@code offset} в документе.
     *
     * <p>Символы идентификатора: буквы (включая кириллицу), цифры, {@code _}, {@code .}.
     * Точка засчитывается только если с обеих сторон от неё стоят буквы/цифры/_
     * (то есть конечная точка отбрасывается).
     *
     * <p>Примеры:
     * <pre>
     *   "Справочники.Валюты.НайтиПоКоду()" → "Справочники.Валюты.НайтиПоКоду"
     *   "СправочникСсылка.Валюты"           → "СправочникСсылка.Валюты"
     * </pre>
     */
    private static String extractQualifiedNameAt(IDocument doc, int offset)
            throws BadLocationException
    {
        String text = doc.get();
        int len = text.length();
        if (offset < 0 || offset >= len) return null;

        // Расширяем влево
        int start = offset;
        while (start > 0 && isQNameChar(text.charAt(start - 1)))
            start--;

        // Расширяем вправо
        int end = offset;
        while (end < len && isQNameChar(text.charAt(end)))
            end++;

        if (start >= end) return null;

        // Убираем ведущие/завершающие точки
        String raw = text.substring(start, end);
        raw = raw.replaceAll("^\\.+|\\.+$", ""); //$NON-NLS-1$

        return raw.isBlank() ? null : raw;
    }

    /** Символы, входящие в составной идентификатор 1С (включая точку-разделитель). */
    private static boolean isQNameChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /**
     * Получает текст из SWT-виджета в фокусе.
     * Поддерживает {@link org.eclipse.swt.widgets.Text},
     * {@link org.eclipse.swt.widgets.Combo},
     * {@link org.eclipse.swt.custom.StyledText} и {@link MoxelControl}.
     * Возвращает выделение (если есть) или всё содержимое поля/ячейки.
     */
    private static String getTextFromFocusedWidget()
    {
        org.eclipse.swt.widgets.Display display =
                org.eclipse.swt.widgets.Display.getDefault();
        if (display == null || display.isDisposed()) 
            return null;
        // getFocusControl() должен вызываться из UI-потока; мы уже в нём.
        org.eclipse.swt.widgets.Control focused = display.getFocusControl();
        if (focused == null || focused.isDisposed()) 
            return null;
        if (focused instanceof org.eclipse.swt.widgets.Text)
        {
            org.eclipse.swt.widgets.Text text = (org.eclipse.swt.widgets.Text) focused;
            String sel = text.getSelectionText();
            if (sel != null && !sel.isBlank()) 
                return sel.strip();
            return text.getText().strip();
        }
        if (focused instanceof org.eclipse.swt.widgets.Combo)
        {
            org.eclipse.swt.widgets.Combo combo = (org.eclipse.swt.widgets.Combo) focused;
            // Combo не предоставляет getSelectionText(), используем bounds
            org.eclipse.swt.graphics.Point pt = combo.getSelection();
            String all = combo.getText();
            if (pt.x < pt.y && pt.y <= all.length())
                return all.substring(pt.x, pt.y).strip();
            return all.strip();
        }
        if (focused instanceof org.eclipse.swt.custom.StyledText)
        {
            org.eclipse.swt.custom.StyledText st = (org.eclipse.swt.custom.StyledText) focused;
            String sel = st.getSelectionText();
            if (sel != null && !sel.isBlank()) 
                return sel.strip();
            return st.getText();
        }
        MoxelControl moxel = focused instanceof MoxelControl
                ? (MoxelControl) focused
                : findMoxelControlAncestor(focused);
        if (moxel != null)
            return getTextFromMoxelCell(moxel);
        return null;
    }

    /** Ищет {@link MoxelControl} среди предков виджета (встроенный редактор ячейки и т. п.). */
    private static MoxelControl findMoxelControlAncestor(org.eclipse.swt.widgets.Control control)
    {
        for (org.eclipse.swt.widgets.Composite parent = control.getParent();
                parent != null && !parent.isDisposed();
                parent = parent.getParent())
        {
            if (parent instanceof MoxelControl)
                return (MoxelControl) parent;
        }
        return null;
    }

    /**
     * Текст активной ячейки табличного документа.
     * Координаты берутся из {@link MoxelControl#getActiveCell()},
     * при их отсутствии — из текущего {@link CellsSelection}.
     */
    private static String getTextFromMoxelCell(MoxelControl moxel)
    {
        if (moxel == null || moxel.isDisposed())
            return null;
        SheetAccessor sheet = moxel.getSheet();
        if (sheet == null)
            return null;

        int row = -1;
        int col = -1;
        Point active = moxel.getActiveCell();
        if (active != null)
        {
            row = active.y;
            col = active.x;
        }
        else
        {
            Selection sel = moxel.getTailSelection();
            if (sel instanceof CellsSelection)
            {
                Rectangle r = ((CellsSelection) sel).getNormalizedPosition();
                row = r.y;
                col = r.x;
            }
        }
        if (row < 0 || col < 0)
            return null;

        Cell cell = sheet.lookupCell(row, col);
        if (cell == null)
            cell = sheet.getCell(row, col);
        if (cell == null)
            return null;

        String text = sheet.getCellText(cell);
        return text != null ? text.strip() : null;
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
