
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream.GetField;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamException;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.label.GlobalDescriptionLabelProvider;
import com.google.inject.Injector;

import javafx.scene.shape.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import com._1c.g5.v8.bm.core.BmPlatform;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.export.ExportException;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com.google.inject.Inject;

import tormozit.edt.assist.ContentAssistAutoOpenSettings;

/**
 * Глобальная команда «Перейти к определению» — порт 1С-процедуры ПерейтиПоСсылкеМД из RDT.
 *
 * <p>Берёт ссылку из буфера обмена и открывает соответствующий редактор EDT.
 * Поддерживаемые форматы ссылок:
 * <ul>
 *   <li>{@code {Справочник.Справочник1.МодульОбъекта(41)}} — строка модуля платформы</li>
 *   <li>{@code {Справочник.Справочник1.МодульОбъекта(41:Метод,1)}} — строка метода модуля</li>
 *   <li>{@code src/cf/Catalogs/Name/Ext/ObjectModule.bsl:3} — Git-путь к BSL</li>
 *   <li>{@code Справочник.Валюты.Форма.ФормаЭлемента} — полное имя объекта МД</li>
 *   <li>{@code Справочник.Валюты.Форма.ФормаЭлемента.ЭлементыФормы.Клиент} — элемент формы</li>
 *   <li>{@code СправочникСсылка.Валюты} — имя типа данных (суффикс отрезается)</li>
 *   <li>{@code СправочникСсылка.Валюты, ДокументСсылка.Накладная} — описание типов</li>
 *   <li>{@code БД.Справочник.Контрагенты.Поле} — имя колонки БД (префикс «БД.» отрезается)</li>
 *   <li>{@code ПакетXDTO.FNS.АдрРФТип} — тип XDTO</li>
 *   <li>{@code https://...} — гиперссылка, открывается в браузере</li>
 *   <li>{@code Расширение1 Справочник.Валюты.МодульОбъекта} — объект расширения</li>
 * </ul>
 *
 * <p>Для открытия редакторов использует:
 * <ul>
 *   <li>{@link IDE#openEditor} для BSL-файлов (модули, формы)</li>
 *   <li>{@code com._1c.g5.v8.dt.ui.util.OpenHelper} (через рефлексию) для EObject EDT</li>
 *   <li>{@link CompareConfigOpenObjectHandler#openInEditor} как запасной вариант</li>
 * </ul>
 */
public class GoToDefinition extends AbstractHandler
{
    // IQualifiedNameFilePathConverter получается через getQualifiedNameConverter(IFile),
    // а не через @Inject — AbstractHandler создаётся Eclipse, а не Guice-инжектором.
    // =======================================================================
    // МАППИНГИ: 1С-тип → папка EDT
    // =======================================================================

    /**
     * Русское название типа МД → имя папки в EDT-проекте (src/cf/<folder>/<ObjectName>/…)
     */
    static final Map<String, String> TYPE_TO_FOLDER = new LinkedHashMap<>();
    static {
        TYPE_TO_FOLDER.put("Справочник",                     "Catalogs");
        TYPE_TO_FOLDER.put("Документ",                       "Documents");
        TYPE_TO_FOLDER.put("ОбщийМодуль",                    "CommonModules");
        TYPE_TO_FOLDER.put("Перечисление",                   "Enums");
        TYPE_TO_FOLDER.put("ПланОбмена",                     "ExchangePlans");
        TYPE_TO_FOLDER.put("Обработка",                      "DataProcessors");
        TYPE_TO_FOLDER.put("Отчет",                          "Reports");
        TYPE_TO_FOLDER.put("ОбщаяФорма",                     "CommonForms");
        TYPE_TO_FOLDER.put("ОбщийМакет",                     "CommonTemplates");
        TYPE_TO_FOLDER.put("ОбщаяКартинка",                  "CommonPictures");
        TYPE_TO_FOLDER.put("ОбщаяКоманда",                   "CommonCommands");
        TYPE_TO_FOLDER.put("ПланВидовХарактеристик",         "ChartsOfCharacteristicTypes");
        TYPE_TO_FOLDER.put("ПланСчетов",                     "ChartsOfAccounts");
        TYPE_TO_FOLDER.put("ПланВидовРасчета",               "ChartsOfCalculationTypes");
        TYPE_TO_FOLDER.put("РегистрСведений",                "InformationRegisters");
        TYPE_TO_FOLDER.put("РегистрНакопления",              "AccumulationRegisters");
        TYPE_TO_FOLDER.put("РегистрБухгалтерии",             "AccountingRegisters");
        TYPE_TO_FOLDER.put("РегистрРасчета",                 "CalculationRegisters");
        TYPE_TO_FOLDER.put("Последовательность",             "Sequences");
        TYPE_TO_FOLDER.put("БизнесПроцесс",                  "BusinessProcesses");
        TYPE_TO_FOLDER.put("Задача",                         "Tasks");
        TYPE_TO_FOLDER.put("ВнешнийИсточникДанных",         "ExternalDataSources");
        TYPE_TO_FOLDER.put("ОбщийАтрибут",                   "CommonAttributes");
        TYPE_TO_FOLDER.put("Константа",                      "Constants");
        TYPE_TO_FOLDER.put("ОпределяемыйТип",                "DefinedTypes");
        TYPE_TO_FOLDER.put("ПодпискаНаСобытие",              "EventSubscriptions");
        TYPE_TO_FOLDER.put("РегламентноеЗадание",            "ScheduledJobs");
        TYPE_TO_FOLDER.put("Роль",                           "Roles");
        TYPE_TO_FOLDER.put("ФункциональнаяОпция",            "FunctionalOptions");
        TYPE_TO_FOLDER.put("ПараметрФункциональнойОпции",    "FunctionalOptionsParameters");
        TYPE_TO_FOLDER.put("Подсистема",                     "Subsystems");
        TYPE_TO_FOLDER.put("ЯзыкКонфигурации",              "Languages");
        TYPE_TO_FOLDER.put("ОбщийМодульПовторногоИспользования", "CommonModules"); // псевдоним
        TYPE_TO_FOLDER.put("ПакетXDTO",                      "XDTOPackages");
        TYPE_TO_FOLDER.put("WebСервис",                      "WebServices");
        TYPE_TO_FOLDER.put("HTTPСервис",                     "HTTPServices");
        TYPE_TO_FOLDER.put("IntegrationService",             "IntegrationServices");
        TYPE_TO_FOLDER.put("СтильОформления",                "StyleItems");
        TYPE_TO_FOLDER.put("Интерфейс",                      "Interfaces");
    }

    /**
     * Суффикс модуля в ссылке платформы → имя BSL-файла в EDT.
     * Используется для разбора {@code Тип.Объект.МодульОбъекта}.
     */
    static final Map<String, String> MODULE_SUFFIX_TO_BSL = new LinkedHashMap<>();
    static {
        MODULE_SUFFIX_TO_BSL.put("МодульОбъекта",            "ObjectModule.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульМенеджера",          "ManagerModule.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульНабораЗаписей",      "RecordSetModule.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульФормы",              "Form.bsl"); // разворачивается контекстом
        MODULE_SUFFIX_TO_BSL.put("МодульМодуля",             "Module.bsl"); // ОбщийМодуль
        MODULE_SUFFIX_TO_BSL.put("МодульОбщийМодуль",        "Module.bsl"); // ОбщийМодуль альт.
        MODULE_SUFFIX_TO_BSL.put("МодульОбъектаБизнесПроцесса", "ObjectModule.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульЗадачи",             "ObjectModule.bsl");
    }

    /**
     * Тип+суффикс данных → базовый тип МД.
     * Например: «СправочникСсылка» → «Справочник».
     */
    static final Map<String, String> TYPE_SUFFIX_MAP = new LinkedHashMap<>();
    static {
        addSuffix("Справочник",           "Ссылка", "Объект", "Менеджер", "Выборка",
                                          "МенеджерТаблицы", "Список");
        addSuffix("Документ",             "Ссылка", "Объект", "Менеджер", "Выборка",
                                          "МенеджерТаблицы", "Список");
        addSuffix("Перечисление",         "Ссылка", "Менеджер");
        addSuffix("ПланОбмена",           "Ссылка", "Объект", "Менеджер");
        addSuffix("ПланВидовХарактеристик","Ссылка","Объект", "Менеджер");
        addSuffix("ПланСчетов",           "Ссылка", "Объект", "Менеджер");
        addSuffix("ПланВидовРасчета",     "Ссылка", "Объект", "Менеджер");
        addSuffix("БизнесПроцесс",        "Ссылка", "Объект", "Менеджер");
        addSuffix("Задача",               "Ссылка", "Объект", "Менеджер");
        addSuffix("РегистрСведений",      "НаборЗаписей", "Запись", "Менеджер",
                                          "МенеджерТаблицы", "Ключ");
        addSuffix("РегистрНакопления",    "НаборЗаписей", "Запись", "Менеджер",
                                          "МенеджерТаблицы", "Ключ",
                                          "ВыборкаДетальныеЗаписи", "ВыборкаОстатки",
                                          "ВыборкаОстаткиИОбороты", "ВыборкаОбороты");
        addSuffix("РегистрБухгалтерии",   "НаборЗаписей", "Запись", "Менеджер",
                                          "МенеджерТаблицы", "Ключ");
        addSuffix("РегистрРасчета",       "НаборЗаписей", "Запись", "Менеджер",
                                          "МенеджерТаблицы", "Ключ");
        addSuffix("ВнешняяОбработка",     "Объект");
        addSuffix("ВнешнийОтчет",         "Объект");
    }

    private static void addSuffix(String base, String... suffixes) {
        for (String s : suffixes)
            TYPE_SUFFIX_MAP.put(base + s, base);
    }

    // =======================================================================
    // РЕГУЛЯРНЫЕ ВЫРАЖЕНИЯ
    // =======================================================================

    /**
     * Ссылка строки модуля платформы:
     * {@code {[Расширение ]Тип.Объект.Модуль(строка[:Метод,смещение])}}
     *
     * <p>Группы: 1=расширение (null если нет), 2=путь модуля, 3=строка, 4=метод, 5=смещение
     */
    private static final Pattern MODULE_LINE_REF = Pattern.compile(
        "\\{" +
        "(?:([А-ЯЁа-яёA-Za-z0-9]+)\\s)?" +  // g1: расширение (необязательно)
        "([А-ЯЁа-яёA-Za-z0-9._/\\\\]+)" +    // g2: путь модуля
        "\\((\\d+)" +                          // g3: номер строки
        "(?::([А-ЯЁа-яёA-Za-z_][А-ЯЁа-яёA-Za-z0-9_]*)," + // g4: метод (необязательно)
        "(\\d+))?" +                           // g5: смещение
        "\\)\\}");

    /**
     * Git-путь к BSL-файлу с номером строки.
     * Примеры: {@code src/cf/Catalogs/Name/Ext/ObjectModule.bsl:3}
     *           {@code Catalogs/Name/Ext/ObjectModule.bsl:3}
     *
     * <p>Группы: 1=путь, 2=строка
     */
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
        File newFile = null, oldFile = null;
        IRApplicationRegistry.IrSession irSession = null;
        IProject project = null;
        long PID = 0;
        String command = "";
        String transportFolder = IRApplicationRegistry.transportFolder;
        File commandFile = new File(transportFolder + "\\Команда.txt");
        if (true
            && commandFile.exists()
            && System.currentTimeMillis() - commandFile.lastModified() < 5000)
        {
            try
            {
                command = Files.readString(commandFile.toPath());
                Files.delete(commandFile.toPath());
                ObjectMapper mapper = new ObjectMapper();
                JsonNode commandObject = mapper.readTree(command);
                command = commandObject.get("Команда").asText();
                PID = commandObject.get("ИДПроцесса").asLong();
                irSession = IRApplicationRegistry.getSession(PID);
                project = irSession.project;
            }
            catch (IOException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
//            МаркерВыводаСообщения = "<ВывестиСообщение> ";
//            Если СтрНачинаетсяС(ЗначениеИзБуфера, МаркерВыводаСообщения) Тогда
//                Текст = ПоследнийФрагментЛкс(ЗначениеИзБуфера, МаркерВыводаСообщения);
//                ТурбоКонф.ПоказатьВсплывающееУведомление(НазваниеСкрипта(), Текст,, ЭтотОбъект, "ПоказатьПриложениеИР",, "Показать приложение ИР"); 
//                ТурбоКонф.ОтжатьМодификаторыПослеЗавершенияСкрипта = Истина;
//                Возврат Истина;
//            КонецЕсли;
            newFile = new File(transportFolder.toString() + "\\НовыйТекст.txt");
            oldFile = new File(transportFolder.toString() + "\\СтарыйТекст.txt");
        }
        Shell shell = HandlerUtil.getActiveShell(event);
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        String ref = getClipboardText(shell);
        if (ref == null || ref.isBlank())
        {
            EclipseToastNotification.show("Перейти к определению", //$NON-NLS-1$
                "Буфер обмена пуст.", 4000); //$NON-NLS-1$
            return null;
        }
        ref = ref.strip();
        Global.log("GoToDefinition: ref = " + ref); //$NON-NLS-1$

        if (!jump(ref, shell, page))
            EclipseToastNotification.show("Перейти к определению", //$NON-NLS-1$
                "Не удалось перейти по ссылке:\n" + truncate(ref, 120), 5000); //$NON-NLS-1$

         if (newFile != null) {
             if (command.contains("Макет.")) {
                 // Мультиметка260525_210353
                 boolean allowImport = false;
                 String currentFilename = null;
                 DtGranularEditorEmbeddedEditorPage dcsEditor = (DtGranularEditorEmbeddedEditorPage)page;
                 try
                {
                     currentFilename = DataCompositionSchemaEditorHook.exportToFile(dcsEditor);
                     allowImport = Files.readString(new File(currentFilename).toPath()) == Files.readString(oldFile.toPath());
                }
                catch (Exception e)
                {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
               if (allowImport)
                {
                   DataCompositionSchemaEditorHook.importFromFile(dcsEditor, newFile);
                }
               else {
                   EclipseToastNotification.show(ref, "Объект был изменен в EDT после начала редактирования в приложении ИР. Загрузка не выполнена. Временный файл новой версии - " + newFile.toString(), 10000);
               }
             }
         }
        return null;
    }

    // =======================================================================
    // ГЛАВНЫЙ ДИСПЕТЧЕР (ПерейтиПоСсылкеМД — EDT-часть)
    // =======================================================================

    /**
     * Пытается перейти по ссылке в EDT.
     * Порт EDТ-применимых частей 1С-функций {@code ПерейтиКОпределению} и
     * {@code ПерейтиПоСсылкеМД} из RDT.os.
     *
     * @return {@code true} если переход выполнен
     */
    public static boolean jump(String raw, Shell shell, IWorkbenchPage page)
    {
        if (raw == null) return false;
        String ref = raw.strip();
        if (ref.isEmpty()) return false;

        // 1. Гиперссылка → открываем в браузере (ГиперСсылка в 1С → ЗапуститьПриложение)
        if (ref.startsWith("http://") || ref.startsWith("https://")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            openUrl(ref);
            return true;
        }

        // 2. Ссылки строк модулей: {[Расш ]Тип.Объект.МодульОбъекта(строка[:Метод,смещ])}
        //    Аналог СтруктураСсылкиСтрокиМодуляЛкс + открытие через список точек останова
        if (ref.contains("{") && ref.contains("}")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return handleModuleLineRefs(ref, shell, page);
        }

        // 3. Git-путь к BSL-файлу: [src/[cf/|ext/Ext/]]Type/Name/Ext/Module.bsl:N
        Matcher gitM = GIT_FILE_REF.matcher(ref);
        if (gitM.matches())
            return openGitFileRef(gitM.group(1), parseInt(gitM.group(2)), page, shell);

        // 4. ИмяКолонкиБД → убираем префикс «БД.» (ОткрытьКолонкуБДЛкс в 1С)
        if (ref.startsWith("БД.")) //$NON-NLS-1$
            ref = ref.substring(3);

        // 5. ПакетXDTO.ИмяПакета[.ИмяТипа] → ищем как объект МД
        if (ref.startsWith("ПакетXDTO.")) //$NON-NLS-1$
            return openXdtoRef(ref, shell, page);

        // 6. ОписаниеТипов: несколько типов через запятую (ИменаМетаданныхИзОписанияТиповЛкс)
        if (ref.contains(",")) //$NON-NLS-1$
        {
            List<String> names = extractMdNamesFromTypeDescription(ref);
            if (!names.isEmpty())
            {
                if (names.size() == 1) return openByFullName(names.get(0), shell, page);
                return pickAndOpen(names, shell, page); // выбор из нескольких (ВыбратьЭлементСпискаЗначений)
            }
        }

        // 7. ИмяТипа: суффикс типа данных → базовый тип МД
        //    Например «СправочникСсылка.Валюты» → «Справочник.Валюты»
        String stripped = stripTypeSuffix(ref);
        if (stripped != null)
            return openByFullName(stripped, shell, page);

        // 8. Полное имя МД: «Справочник.Валюты.Форма.ФормаЭлемента» и т.п.
        return openByFullName(ref, shell, page);
    }

    // =======================================================================
    // ССЫЛКИ СТРОК МОДУЛЕЙ  {Тип.Объект.МодульОбъекта(41)}
    // =======================================================================

    private static boolean handleModuleLineRefs(String text, Shell shell, IWorkbenchPage page)
    {
        List<ModuleLineRef> refs = parseAllModuleLineRefs(text);
        if (refs.isEmpty()) return false;

        // Несколько ссылок (стек вызовов) → показываем выбор,
        // аналог ОткрытьВыборСтрокиСтекаЛкс в 1С
        ModuleLineRef chosen;
        if (refs.size() == 1)
        {
            chosen = refs.get(0);
        }
        else
        {
            String[] labels = refs.stream()
                .map(r -> r.displayLabel())
                .toArray(String[]::new);
            MessageDialog dlg = new MessageDialog(shell,
                "Выберите строку стека", null, //$NON-NLS-1$
                "Найдено несколько ссылок. Выберите строку для перехода:", //$NON-NLS-1$
                MessageDialog.QUESTION, labels, 0);
            int idx = dlg.open();
            if (idx < 0 || idx >= refs.size()) return false;
            chosen = refs.get(idx);
        }
        return openModuleLineRef(chosen, page, shell);
    }

    private static boolean openModuleLineRef(ModuleLineRef r, IWorkbenchPage page, Shell shell)
    {
        // Внешний файл (расширение или внешняя обработка): путь уже в r.file
        if (r.file != null)
            return openGitFileRef(r.file, r.line, page, shell);

        // Строим путь к BSL-файлу по имени модуля
        String bslPath = moduleToBslPath(r.modulePath, r.extension);
        if (bslPath == null)
        {
            Global.log("GoToDefinition: не могу построить путь для " + r.modulePath); //$NON-NLS-1$
            return false;
        }
        return openBslFileAt(bslPath, r.line, page, shell);
    }

    // =======================================================================
    // GIT-ССЫЛКА НА ФАЙЛ  src/cf/Catalogs/Name/Ext/ObjectModule.bsl:3
    // =======================================================================

    private static boolean openGitFileRef(String filePath, int line, IWorkbenchPage page, Shell shell)
    {
        IFile file = findFileInWorkspace(filePath, page);
        if (file == null)
        {
            // Добавляем src/cf/ если ещё не было
            if (!filePath.startsWith("src/")) //$NON-NLS-1$
            {
                file = findFileInWorkspace("src/cf/" + filePath, page); //$NON-NLS-1$
            }
        }
        if (file == null)
        {
            Global.log("GoToDefinition: файл не найден: " + filePath); //$NON-NLS-1$
            return false;
        }
        return openFileAtLine(file, line, page, shell);
    }

    // =======================================================================
    // XDTO  ПакетXDTO.FNS.АдрРФТип
    // =======================================================================

    private static boolean openXdtoRef(String ref, Shell shell, IWorkbenchPage page)
    {
        // ПакетXDTO.ИмяПакета[.ИмяТипа]
        // В EDT XDTOPackages хранятся в XDTOPackages/<PackageName>/
        // Пробуем открыть объект пакета (без имени типа)
        String[] parts = ref.split("\\.", 3); // ["ПакетXDTO","PkgName","TypeName"]
        if (parts.length < 2) return false;
        String packageMdName = "ПакетXDTO." + parts[1]; //$NON-NLS-1$
        return openMdObjectByFullName(packageMdName, shell, page);
    }

    // =======================================================================
    // ПОЛНОЕ ИМЯ МД  Справочник.Валюты.Форма.ФормаЭлемента[.Элементы.Клиент]
    // =======================================================================

    /**
     * Открывает объект МД по его полному имени (ЭтоПолноеИмяМД в 1С).
     *
     * <p>Порядок попыток:
     * <ol>
     *   <li>Если имя — путь модуля (оканчивается на известный суффикс) → BSL-файл</li>
     *   <li>Если содержит «.Форма.» → открываем BSL модуля формы и/или форму</li>
     *   <li>Открываем через EDT OpenHelper (рефлексия)</li>
     *   <li>Ищем .mdo-файл в воркспейсе и открываем через IDE</li>
     * </ol>
     */
    public static boolean openByFullName(String fullName, Shell shell, IWorkbenchPage page)
    {
        if (fullName == null || fullName.isBlank()) return false;
        fullName = fullName.strip();

        // Расширение в начале: «Расширение1 Справочник.Валюты.МодульОбъекта»
        String extension = null;
        int spaceIdx = fullName.indexOf(' ');
        if (spaceIdx > 0 && !fullName.substring(0, spaceIdx).contains(".")) //$NON-NLS-1$
        {
            extension = fullName.substring(0, spaceIdx);
            fullName  = fullName.substring(spaceIdx + 1);
        }

        // a) Путь к модулю (заканчивается на МодульОбъекта и т.п.) → BSL
        if (isModuleSuffixPath(fullName))
        {
            String bslPath = moduleToBslPath(fullName, extension);
            if (bslPath != null) return openBslFileAt(bslPath, 0, page, shell);
        }

        // б) Содержит .Форма. → форма или элемент формы
        //    ЧастиПолногоИмениЭлементаФормы в 1С
        int formPos = indexOfFormPart(fullName);
        if (formPos >= 0)
        {
            // Извлекаем имя формы и пробуем открыть её модуль
            String formBsl = formNameToBslPath(fullName, formPos, extension);
            if (formBsl != null && openBslFileAt(formBsl, 0, page, shell))
                return true;
            // Если не вышло — открываем объект-владелец формы
            String ownerName = fullName.substring(0, formPos);
            return openMdObjectByFullName(ownerName, shell, page);
        }

        // в) Открываем через OpenHelper / .mdo в воркспейсе
        return openMdObjectByFullName(fullName, shell, page);
    }

    // =======================================================================
    // EDT: ОТКРЫТИЕ ОБЪЕКТА МД
    // =======================================================================

    /**
     * Открывает объект МД через EDT OpenHelper.
     *
     * <p>Алгоритм (аналог ПерейтиПоСсылкеМД → ЭтоПолноеИмяМД → открыть редактор):
     * <ol>
     *   <li>Строим путь к .mdo-файлу объекта в EDT-проекте.</li>
     *   <li>Получаем EMF {@link ResourceSet} проекта через цепочки рефлексивных вызовов
     *       ({@code getBmModel()} → {@code getResourceSet()} и т.п.).</li>
     *   <li>Загружаем {@link EObject} из .mdo — корневой объект ресурса.</li>
     *   <li>Открываем через {@code OpenHelper.openEditor(EObject)} —
     *       аналогично {@link CompareConfigOpenObjectHandler#openInEditor}.</li>
     *   <li>Запасной вариант: {@link IDE#openEditor} с .mdo-файлом напрямую.</li>
     * </ol>
     */
    private static boolean openMdObjectByFullName(String fullName, Shell shell, IWorkbenchPage page)
    {
        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(Global.getActiveEditorProject(true));
        if (v8Project == null)
        {
            EclipseToastNotification.show("Переход к определению", //$NON-NLS-1$
                "Сначала нужно активировать проект."); //$NON-NLS-1$
            return false;
        }

        String mdoPath = mdNameToMdoPath(fullName);
        if (mdoPath == null)
        {
            Global.log("GoToDefinition: не могу построить .mdo-путь для: " + fullName); //$NON-NLS-1$
            return false;
        }

        IFile mdoFile = findFileInWorkspace(mdoPath, page);
        if (mdoFile == null)
        {
            Global.log("GoToDefinition: .mdo не найден: " + mdoPath); //$NON-NLS-1$
            return false;
        }

        // ── Шаг 1: EObject через ResourceSet проекта ──
        EObject eObject = resolveEObjectViaResourceSet(mdoFile, v8Project);
        if (eObject != null)
        {
            openEObjectInEditor(eObject, page, shell);
            return true;
        }

        // ── Запасной вариант: IDE.openEditor — EDT сам выберет нужный редактор ──
        Global.log("GoToDefinition: EObject не получен, открываем .mdo напрямую"); //$NON-NLS-1$
        try
        {
            IEditorPart ed = IDE.openEditor(page, mdoFile, true);
            return ed != null;
        }
        catch (PartInitException e)
        {
            Global.log("GoToDefinition: IDE.openEditor(.mdo): " + e); //$NON-NLS-1$
            return false;
        }
    }
    
    public static EObject resolveEObjectViaResourceSet(IFile file, IV8Project v8Project)
    {
        URI fileUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(fileUri);
        IQualifiedNameFilePathConverter filePathConverter = rsp.get(IQualifiedNameFilePathConverter.class);
        IBmModelManager modelManager = (IBmModelManager)Global.getServiceByClass(IBmModelManager.class);
        BmPlatform platform = modelManager.getBmPlatform();
        IBmNamespace ns = modelManager.getBmNamespace(v8Project.getProject());
        QualifiedName fqn = filePathConverter.getFqn(file);
        if (fqn == null)
        {
            Global.log("GoToDefinition: getFqn вернул null для " + file.getFullPath()); //$NON-NLS-1$
            return null;
        }

        IBmPlatformTransaction transaction = platform.beginReadOnlyTransaction(true);
        EObject topObject = null;
        try
        {
            topObject = (EObject) transaction.getTopObjectByFqn(ns, fqn.toString());
        }
        catch (Exception e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        finally {
            transaction.commit();
        }
        return topObject;
    }

    /**
     * Открывает EDT-редактор для {@link EObject} через {@code OpenHelper.openEditor}.
     * Аналог {@link CompareConfigOpenObjectHandler#openInEditor}, но принимает
     * {@link IWorkbenchPage} вместо {@link IEditorPart}.
     */
    private static void openEObjectInEditor(EObject eObject, IWorkbenchPage page, Shell shell)
    {
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper"); //$NON-NLS-1$
            Object   helper = cls.getConstructor().newInstance();

            // Приоритет 1: openEditor(EObject)
            for (Method m : cls.getMethods())
            {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 1) continue; //$NON-NLS-1$
                if (m.getParameterTypes()[0].isAssignableFrom(eObject.getClass()))
                {
                    Object result = m.invoke(helper, eObject);
                    if (result != null) return;
                }
            }
            // Приоритет 2: openEditor(EObject, IWorkbenchPage)
            for (Method m : cls.getMethods())
            {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 2) continue; //$NON-NLS-1$
                if (m.getParameterTypes()[0].isAssignableFrom(eObject.getClass()))
                {
                    try { m.invoke(helper, eObject, page); return; }
                    catch (Exception ignored) {}
                }
            }
            Global.log("GoToDefinition: OpenHelper не нашёл openEditor для " //$NON-NLS-1$
                + eObject.getClass().getSimpleName());
        }
        catch (ClassNotFoundException e)
        {
            Global.log("GoToDefinition: OpenHelper не найден: " + e); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log("GoToDefinition: openEObjectInEditor: " + e); //$NON-NLS-1$
        }
    }

    // =======================================================================
    // ФАЙЛОВЫЕ ОПЕРАЦИИ
    // =======================================================================

    private static boolean openBslFileAt(String bslPath, int line, IWorkbenchPage page, Shell shell)
    {
        IFile file = findFileInWorkspace(bslPath, page);
        if (file == null)
        {
            Global.log("GoToDefinition: BSL не найден: " + bslPath); //$NON-NLS-1$
            return false;
        }
        return openFileAtLine(file, line, page, shell);
    }

    private static boolean openFileAtLine(IFile file, int line, IWorkbenchPage page, Shell shell)
    {
        try
        {
            IEditorPart editor = IDE.openEditor(page, file, true);
            if (editor == null) return false;
            if (line > 0) revealLine(editor, line);
            return true;
        }
        catch (PartInitException e)
        {
            Global.log("GoToDefinition: openFileAtLine: " + e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Переводит курсор в открытом редакторе на указанную строку.
     * Аналог «перейти в позицию» в 1С.
     */
    private static void revealLine(IEditorPart editor, int lineNumber)
    {
        if (lineNumber <= 0 || !(editor instanceof ITextEditor)) return;
        ITextEditor te  = (ITextEditor) editor;
        IDocument   doc = te.getDocumentProvider().getDocument(te.getEditorInput());
        if (doc == null) return;
        try
        {
            int offset = doc.getLineOffset(Math.max(0, lineNumber - 1));
            te.selectAndReveal(offset, 0);
        }
        catch (BadLocationException e)
        {
            Global.log("GoToDefinition: revealLine " + lineNumber + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Ищет IFile в проекте активного редактора, затем во всех открытых проектах.
     * Поддерживает пути без src/cf/ и с ним.
     */
    private static IFile findFileInWorkspace(String relPath, IWorkbenchPage page)
    {
        String path = relPath.replace('\\', '/');

        IProject active = getActiveProject(page);
        if (active != null)
        {
            IFile f = findInProject(active, path);
            if (f != null) return f;
        }
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (!p.isOpen() || p.equals(active)) continue;
            IFile f = findInProject(p, path);
            if (f != null) return f;
        }
        return null;
    }

    private static IFile findInProject(IProject project, String path)
    {
        // Прямой путь
        IFile f = project.getFile(path);
        if (f.exists()) return f;
        return null;
    }

    // =======================================================================
    // КОНВЕРТАЦИЯ ИМЁН МОДУЛЕЙ → ПУТИ BSL
    // =======================================================================

    /**
     * Конвертирует путь модуля из формата ссылки платформы в путь к BSL-файлу EDT.
     *
     * <p>Примеры:
     * <pre>
     *   ОбщийМодуль.МойМодуль              → src/cf/CommonModules/МойМодуль/Ext/Module.bsl
     *   ОбщаяФорма.МояФорма                → src/cf/CommonForms/МояФорма/Ext/Form.bsl
     *   Справочник.Валюты.МодульОбъекта    → src/cf/Catalogs/Валюты/Ext/ObjectModule.bsl
     *   Справочник.Валюты.МодульМенеджера  → src/cf/Catalogs/Валюты/Ext/ManagerModule.bsl
     *   Справочник.Валюты.Форма.ФормаЭлемента → src/cf/Catalogs/Валюты/Forms/ФормаЭлемента/Ext/Form.bsl
     *   Обработка.МояОбр.МодульОбъекта     → src/cf/DataProcessors/МояОбр/Ext/ObjectModule.bsl
     * </pre>
     *
     * @param modulePath путь вида «Тип.Объект.МодульXxx» или «Тип.Объект.Форма.ИмяФормы»
     * @param extension  имя расширения (если не null, используется src/ext/<extension>/ вместо src/cf/)
     */
    static String moduleToBslPath(String modulePath, String extension)
    {
        String[] parts = modulePath.split("\\.", -1); //$NON-NLS-1$
        if (parts.length == 0) return null;

        String typeRu = parts[0];
        String folder = TYPE_TO_FOLDER.get(typeRu);
        if (folder == null) return null;

        String prefix = extension != null
            ? "src/ext/" + extension + "/" + folder //$NON-NLS-1$ //$NON-NLS-2$
            : "src/cf/" + folder; //$NON-NLS-1$

        // ОбщийМодуль.МойМодуль → .../МойМодуль/Ext/Module.bsl
        if ("CommonModules".equals(folder) && parts.length >= 2) //$NON-NLS-1$
            return prefix + "/" + parts[1] + "/Ext/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

        // ОбщаяФорма.МояФорма → .../МояФорма/Ext/Form.bsl
        if ("CommonForms".equals(folder) && parts.length >= 2) //$NON-NLS-1$
            return prefix + "/" + parts[1] + "/Ext/Form.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

        if (parts.length < 2) return null;
        String objectName = parts[1];

        // Тип.Объект.Форма.ИмяФормы[.МодульФормы] → .../Forms/ИмяФормы/Ext/Form.bsl
        for (int i = 2; i < parts.length - 1; i++)
        {
            if ("Форма".equals(parts[i]) && i + 1 < parts.length) //$NON-NLS-1$
            {
                String formName = parts[i + 1];
                return prefix + "/" + objectName + "/Forms/" + formName + "/Ext/Form.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        // Тип.Объект.МодульОбъекта / МодульМенеджера / ...
        if (parts.length >= 3)
        {
            String suffix  = parts[parts.length - 1];
            String bslFile = MODULE_SUFFIX_TO_BSL.get(suffix);
            if (bslFile != null)
                return prefix + "/" + objectName + "/Ext/" + bslFile; //$NON-NLS-1$ //$NON-NLS-2$
        }

        return null; // только Тип.Объект — не достаточно для BSL
    }

    /**
     * Полное имя МД → путь к .mdo-файлу объекта.
     * Например: «Справочник.Валюты» → «src/cf/Catalogs/Валюты/Валюты.mdo»
     */
    private static String mdNameToMdoPath(String fullName)
    {
        String[] parts = fullName.split("\\.", 3); //$NON-NLS-1$
        if (parts.length < 2) return null;

        String folder = TYPE_TO_FOLDER.get(parts[0]);
        if (folder == null) return null;

        String objectName = parts[1];
        // EDT хранит .mdo рядом с папкой объекта: ObjectName/ObjectName.mdo
        return "src/" + folder + "/" + objectName + "/" + objectName + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Извлекает BSL-путь модуля формы из имени вида
     * {@code Справочник.Валюты.Форма.ФормаЭлемента[.ЭлементыФормы.Клиент]}.
     */
    private static String formNameToBslPath(String fullName, int formIdx, String extension)
    {
        String[] parts = fullName.split("\\."); //$NON-NLS-1$
        // Находим позицию «Форма» в массиве
        for (int i = 0; i < parts.length - 1; i++)
        {
            if ("Форма".equals(parts[i]) || "Forms".equals(parts[i])) //$NON-NLS-1$ //$NON-NLS-2$
            {
                // parts[0]=Тип, parts[1]=Объект, parts[i]=Форма, parts[i+1]=ИмяФормы
                if (i >= 1 && i + 1 < parts.length)
                {
                    String typeRu    = parts[0];
                    String objName   = parts[1];
                    String formName  = parts[i + 1];
                    String folder    = TYPE_TO_FOLDER.get(typeRu);
                    if (folder == null) return null;
                    String prefix = extension != null
                        ? "src/ext/" + extension + "/" + folder //$NON-NLS-1$ //$NON-NLS-2$
                        : "src/cf/" + folder; //$NON-NLS-1$
                    return prefix + "/" + objName + "/Forms/" + formName + "/Ext/Form.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        return null;
    }

    // =======================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (аналог утилит 1С)
    // =======================================================================

    /** Убирает суффикс типа: «СправочникСсылка.Валюты» → «Справочник.Валюты» */
    private static String stripTypeSuffix(String ref)
    {
        int dot = ref.indexOf('.');
        if (dot < 0) return null;
        String base = TYPE_SUFFIX_MAP.get(ref.substring(0, dot));
        return base != null ? base + ref.substring(dot) : null;
    }

    /**
     * Извлекает имена объектов МД из описания типов (ИменаМетаданныхИзОписанияТиповЛкс в 1С).
     * «СправочникСсылка.Валюты, ДокументСсылка.Накладная» → [Справочник.Валюты, Документ.Накладная]
     */
    private static List<String> extractMdNamesFromTypeDescription(String types)
    {
        List<String> result = new ArrayList<>();
        for (String part : TYPE_DESCRIPTION_SPLITTER.split(types))
        {
            String t = part.strip();
            if (t.isEmpty()) continue;
            String stripped = stripTypeSuffix(t);
            if (stripped != null)          result.add(stripped);
            else if (t.contains("."))      result.add(t); //$NON-NLS-1$
        }
        return result;
    }

    /** Показывает диалог выбора из нескольких МД-объектов и открывает выбранный. */
    private static boolean pickAndOpen(List<String> names, Shell shell, IWorkbenchPage page)
    {
        String[] buttons = names.toArray(new String[0]);
        MessageDialog dlg = new MessageDialog(shell,
            "Выберите объект", null, //$NON-NLS-1$
            "Найдено несколько объектов. Выберите для перехода:", //$NON-NLS-1$
            MessageDialog.QUESTION, buttons, 0);
        int idx = dlg.open();
        if (idx < 0 || idx >= names.size()) return false;
        return openByFullName(names.get(idx), shell, page);
    }

    private static boolean isModuleSuffixPath(String name)
    {
        for (String suffix : MODULE_SUFFIX_TO_BSL.keySet())
            if (name.endsWith("." + suffix)) return true; //$NON-NLS-1$
        return false;
    }

    /**
     * Возвращает позицию токена «.Форма.» / «.ЭлементыФормы.» / «.Элементы.» в имени,
     * или -1 если не найдено.
     */
    private static int indexOfFormPart(String fullName)
    {
        for (String marker : new String[]{ ".Форма.", ".Forms.", ".ЭлементыФормы.", ".Элементы." }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            int idx = fullName.indexOf(marker);
            if (idx >= 0) return idx;
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
            r.extension  = m.group(1);  // может быть null
            r.modulePath = m.group(2);
            r.line       = parseInt(m.group(3));
            r.method     = m.group(4);  // может быть null
            r.offset     = parseInt(m.group(5));
            // Путь с «/» или «\» — внешний файл
            if (r.modulePath.contains("/") || r.modulePath.contains("\\")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                r.file       = r.modulePath;
                r.modulePath = null;
            }
            result.add(r);
        }
        return result;
    }

    private static final class ModuleLineRef
    {
        String extension;  // имя расширения (null = основная конфигурация)
        String modulePath; // Тип.Объект.МодульXxx (null если file != null)
        String file;       // путь к файлу (для внешних обработок)
        int    line;       // номер строки (1-based)
        String method;     // имя метода (необязательно)
        int    offset;     // смещение внутри метода (необязательно, не используется в EDT)

        String displayLabel()
        {
            String base = modulePath != null ? modulePath : file;
            String s = (base != null ? base : "") + " (" + line + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (method != null) s += " : " + method; //$NON-NLS-1$
            if (extension != null) s += " [" + extension + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            return s;
        }
    }

    // =======================================================================
    // УТИЛИТЫ
    // =======================================================================

    private static void openUrl(String url)
    {
        try { org.eclipse.swt.program.Program.launch(url); }
        catch (Exception e) { Global.log("GoToDefinition: openUrl: " + e); } //$NON-NLS-1$
    }

    private static String getClipboardText(Shell shell)
    {
        Clipboard cb = new Clipboard(shell.getDisplay());
        try { return (String) cb.getContents(TextTransfer.getInstance()); }
        finally { cb.dispose(); }
    }

    private static IProject getActiveProject(IWorkbenchPage page)
    {
        if (page == null) return null;
        IEditorPart ed = page.getActiveEditor();
        if (ed == null) return null;
        IEditorInput input = ed.getEditorInput();
        return (input instanceof IFileEditorInput)
            ? ((IFileEditorInput) input).getFile().getProject()
            : null;
    }

    private static int parseInt(String s)
    {
        if (s == null) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String truncate(String s, int max)
    {
        return s.length() <= max ? s : s.substring(0, max) + "\u2026"; //$NON-NLS-1$
    }
}
