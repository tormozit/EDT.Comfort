
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единственный источник правды для маппинга имён типов метаданных 1С
 * во всех формах написания.
 */
public final class MdTypeMapping
{
    private MdTypeMapping() {}

    // =========================================================================
    // Шесть производных маппингов  RU / EN ед.ч. / EN мн.ч. (папка EDT)
    // =========================================================================

    static final Map<String, String> RU_TO_EN_SING        = new LinkedHashMap<>();
    static final Map<String, String> EN_SING_TO_RU        = new LinkedHashMap<>();
    static final Map<String, String> RU_TO_FOLDER         = new LinkedHashMap<>();
    static final Map<String, String> EN_SING_TO_FOLDER    = new LinkedHashMap<>();
    static final Map<String, String> FOLDER_TO_RU         = new LinkedHashMap<>();
    static final Map<String, String> FOLDER_TO_EN_SING    = new LinkedHashMap<>();

    /**
     * RU ед.ч. → RU мн.ч.  «Справочник» → «Справочники».
     * Используется в {@link #ruToRuPlural} для построения имён менеджеров
     * (например {@code ПрямоеИмяМодуляИзПолного} в ИР).
     */
    private static final Map<String, String> RU_TO_RU_PLURAL = new LinkedHashMap<>();

    // =========================================================================
    // Единая таблица: (RU ед.ч., EN ед.ч., EN мн.ч./папка)
    // =========================================================================

    static
    {
        add("Справочник",                   "Catalog",                      "Catalogs");
        add("Документ",                     "Document",                     "Documents");
        add("Перечисление",                 "Enum",                         "Enums");
        add("ПланОбмена",                   "ExchangePlan",                 "ExchangePlans");
        add("Обработка",                    "DataProcessor",                "DataProcessors");
        add("Отчет",                        "Report",                       "Reports");
        add("БизнесПроцесс",                "BusinessProcess",              "BusinessProcesses");
        add("Задача",                       "Task",                         "Tasks");
        add("Последовательность",           "Sequence",                     "Sequences");

        add("РегистрСведений",              "InformationRegister",          "InformationRegisters");
        add("РегистрНакопления",            "AccumulationRegister",         "AccumulationRegisters");
        add("РегистрБухгалтерии",           "AccountingRegister",           "AccountingRegisters");
        add("РегистрРасчета",               "CalculationRegister",          "CalculationRegisters");

        add("ПланВидовХарактеристик",       "ChartOfCharacteristicTypes",   "ChartsOfCharacteristicTypes");
        add("ПланСчетов",                   "ChartOfAccounts",              "ChartsOfAccounts");
        add("ПланВидовРасчета",             "ChartOfCalculationTypes",      "ChartsOfCalculationTypes");

        add("ОбщийМодуль",                  "CommonModule",                 "CommonModules");
        add("ОбщаяФорма",                   "CommonForm",                   "CommonForms");
        add("ОбщийМакет",                   "CommonTemplate",               "CommonTemplates");
        add("ОбщаяКартинка",                "CommonPicture",                "CommonPictures");
        add("ОбщаяКоманда",                 "CommonCommand",                "CommonCommands");
        add("ОбщийАтрибут",                 "CommonAttribute",              "CommonAttributes");

        add("Константа",                    "Constant",                     "Constants");
        add("ОпределяемыйТип",              "DefinedType",                  "DefinedTypes");
        add("ПодпискаНаСобытие",            "EventSubscription",            "EventSubscriptions");
        add("РегламентноеЗадание",          "ScheduledJob",                 "ScheduledJobs");
        add("ФункциональнаяОпция",          "FunctionalOption",             "FunctionalOptions");
        add("ПараметрФункциональнойОпции",  "FunctionalOptionsParameter",   "FunctionalOptionsParameters");
        add("Роль",                         "Role",                         "Roles");
        add("Подсистема",                   "Subsystem",                    "Subsystems");
        add("ЯзыкКонфигурации",             "Language",                     "Languages");

        add("ВнешнийИсточникДанных",        "ExternalDataSource",           "ExternalDataSources");
        add("ПакетXDTO",                    "XDTOPackage",                  "XDTOPackages");
        add("WebСервис",                    "WebService",                   "WebServices");
        add("HTTPСервис",                   "HTTPService",                  "HTTPServices");
        add("IntegrationService",           "IntegrationService",           "IntegrationServices");
        add("СтильОформления",              "StyleItem",                    "StyleItems");
        add("Интерфейс",                    "Interface",                    "Interfaces");

        addAlias("ОбщийМодульПовторногоИспользования", "ОбщийМодуль");

        // ── Вложенные типы без папки ─────────────────────────────────────────
        add("Конфигурация",                     "Configuration",                null);
        add("МодульУправляемогоПриложения",     "ManagedApplicationModule",     null);
        add("МодульОбычногоПриложения",         "OrdinaryApplicationModule",    null);
        add("МодульВнешнегоСоединения",         "ExternalConnectionModule",     null);
        add("МодульОбъекта",                    "ObjectModule",                 null);
        add("МодульНабораЗаписей",              "RecordSetModule",              null);
        add("МодульМенеджера",                  "ManagerModule",                null);
        add("МодульМенеджераЗначения",          "ValueManagerModule",           null);
        add("МодульКоманды",                    "CommandModule",                null);
        add("МодульСеанса",                     "SessionModule",                null);
        add("Модуль",                           "Module",                       null);
        add("Форма",                            "Form",                         null);
        add("Команда",                          "Command",                      null);

        // ── Под-объекты (BM FQN) ─────────────────────────────────────────────
        add("Макет",               "Template",        null);
        add("ТабличнаяЧасть",      "TabularSection",  null);
        add("Реквизит",            "Attribute",       null);
        add("Измерение",           "Dimension",       null);
        add("Ресурс",              "Resource",        null);
        add("Перерасчет",          "Recalculation",   null);
        add("ПризнакУчета",        "AccountingFlag",  null);

        // ── RU ед.ч. → RU мн.ч. для менеджеров ─────────────────────────────
        // Используется в ПрямоеИмяМодуляИзПолного: МодульМенеджера → «Справочники.Валюты»
        RU_TO_RU_PLURAL.put("Справочник",             "Справочники");
        RU_TO_RU_PLURAL.put("Документ",               "Документы");
        RU_TO_RU_PLURAL.put("Перечисление",           "Перечисления");
        RU_TO_RU_PLURAL.put("ПланОбмена",             "ПланыОбмена");
        RU_TO_RU_PLURAL.put("ПланВидовХарактеристик", "ПланыВидовХарактеристик");
        RU_TO_RU_PLURAL.put("ПланСчетов",             "ПланыСчетов");
        RU_TO_RU_PLURAL.put("ПланВидовРасчета",       "ПланыВидовРасчета");
        RU_TO_RU_PLURAL.put("РегистрСведений",        "РегистрыСведений");
        RU_TO_RU_PLURAL.put("РегистрНакопления",      "РегистрыНакопления");
        RU_TO_RU_PLURAL.put("РегистрБухгалтерии",     "РегистрыБухгалтерии");
        RU_TO_RU_PLURAL.put("РегистрРасчета",         "РегистрыРасчета");
        RU_TO_RU_PLURAL.put("БизнесПроцесс",          "БизнесПроцессы");
        RU_TO_RU_PLURAL.put("Задача",                 "Задачи");
        RU_TO_RU_PLURAL.put("Обработка",              "Обработки");
        RU_TO_RU_PLURAL.put("Отчет",                  "Отчеты");
    }

    // =========================================================================
    // Методы поиска по одному имени типа
    // =========================================================================

    public static String ruToEnSing(String ru)             { return RU_TO_EN_SING.get(ru); }
    public static String enSingToRu(String enSing)         { return EN_SING_TO_RU.get(enSing); }
    public static String ruToFolder(String ru)             { return RU_TO_FOLDER.get(ru); }
    public static String enSingToFolder(String enSing)     { return EN_SING_TO_FOLDER.get(enSing); }
    public static String folderToRu(String folder)         { return FOLDER_TO_RU.get(folder); }
    public static String folderToEnSing(String folder)     { return FOLDER_TO_EN_SING.get(folder); }

    /**
     * «Справочник» → «Справочники».
     * Используется при формировании «прямого имени» менеджера объекта
     * (аналог {@code ирОбщий.ИмяТипаМДМножественноеИзЕдинЛкс} в ИР).
     *
     * @return RU мн.ч., или {@code null} если тип не известен
     */
    public static String ruToRuPlural(String ruSingular)   
    { 
        return RU_TO_RU_PLURAL.get(ruSingular); 
    }

    // =========================================================================
    // Нормализация из любой формы
    // =========================================================================

    public static String anyToRu(String type)
    {
        if (type == null) return null;
        if (RU_TO_EN_SING.containsKey(type)) return type;
        String r = EN_SING_TO_RU.get(type); if (r != null) return r;
        return FOLDER_TO_RU.get(type);
    }

    public static String anyToEnSing(String type)
    {
        if (type == null) return null;
        if (EN_SING_TO_RU.containsKey(type)) return type;
        String e = RU_TO_EN_SING.get(type); if (e != null) return e;
        return FOLDER_TO_EN_SING.get(type);
    }

    public static String anyToFolder(String type)
    {
        if (type == null) return null;
        if (FOLDER_TO_RU.containsKey(type)) return type;
        String f = RU_TO_FOLDER.get(type); if (f != null) return f;
        return EN_SING_TO_FOLDER.get(type);
    }

    // =========================================================================
    // Операции с полным именем («Тип.Имя» или «Папка/Имя»)
    // =========================================================================

    public static String toRuFullName(String fullName)
    {
        Parsed p = parse(fullName); if (p == null) return null;
        String ru = anyToRu(p.type); return ru == null ? null : ru + "." + p.name;
    }

    public static String toEnSingFullName(String fullName)
    {
        Parsed p = parse(fullName); if (p == null) return null;
        String en = anyToEnSing(p.type); return en == null ? null : en + "." + p.name;
    }

    public static String toFolderPath(String fullName)        { return toFolderPath(fullName, '/'); }
    public static String toFolderPath(String fullName, char sep)
    {
        Parsed p = parse(fullName); if (p == null) return null;
        String f = anyToFolder(p.type); return f == null ? null : f + sep + p.name;
    }

    public static String pathToRuFullName(String path)
    {
        Parsed p = parse(path); if (p == null) return null;
        String ru = anyToRu(p.type); return ru == null ? null : ru + "." + p.name;
    }

    public static String pathToEnSingFullName(String path)
    {
        Parsed p = parse(path); if (p == null) return null;
        String en = anyToEnSing(p.type); return en == null ? null : en + "." + p.name;
    }

    // =========================================================================
    // BM URI FQN → полное русское имя
    // =========================================================================

    /**
     * Конвертирует FQN из BM URI в полное русское имя.
     * <pre>
     *   "Catalog.Валюты"                        → "Справочник.Валюты"
     *   "Catalog.Валюты.Template.Классификатор" → "Справочник.Валюты.Макет.Классификатор"
     * </pre>
     */
    public static String bmFqnToRuFullName(String fqn)
    {
        if (fqn == null || fqn.isBlank()) return null;
        String[] parts = fqn.split("\\.", -1);
        if (parts.length < 2) return null;
        String typeRu = anyToRu(parts[0]);
        if (typeRu == null) return null;
        StringBuilder sb = new StringBuilder(typeRu).append('.').append(parts[1]);
        for (int i = 2; i + 1 < parts.length; i += 2)
        {
            String subTypeRu = anyToRu(parts[i]);
            if (subTypeRu != null) sb.append('.').append(subTypeRu).append('.').append(parts[i + 1]);
        }
        return sb.toString();
    }

    // =========================================================================
    // Совместимость
    // =========================================================================

    public static Map<String, String> getRuToFolderMap()
    {
        return Collections.unmodifiableMap(RU_TO_FOLDER);
    }

    // =========================================================================
    // Регистрация
    // =========================================================================

    private static void add(String ru, String enSing, String enPlur)
    {
        RU_TO_EN_SING.put(ru, enSing);
        EN_SING_TO_RU.put(enSing, ru);
        if (enPlur != null)
        {
            RU_TO_FOLDER.put(ru, enPlur);
            EN_SING_TO_FOLDER.put(enSing, enPlur);
            FOLDER_TO_RU.put(enPlur, ru);
            FOLDER_TO_EN_SING.put(enPlur, enSing);
        }
    }

    private static void addAlias(String ruAlias, String ruCanonical)
    {
        String enSing = RU_TO_EN_SING.get(ruCanonical);
        String folder = RU_TO_FOLDER.get(ruCanonical);
        if (enSing != null) RU_TO_EN_SING.put(ruAlias, enSing);
        if (folder != null) RU_TO_FOLDER.put(ruAlias, folder);
    }

    // =========================================================================
    // Разбор строки «Тип.Имя» или «Папка/Имя»
    // =========================================================================

    private static final class Parsed { final String type, name; Parsed(String t, String n) { type=t; name=n; } }

    private static Parsed parse(String s)
    {
        if (s == null || s.isBlank()) return null;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '.' || c == '/' || c == '\\')
            {
                String type = s.substring(0, i), name = s.substring(i + 1);
                return (type.isBlank() || name.isBlank()) ? null : new Parsed(type, name);
            }
        }
        return null;
    }
    public static String ruToEnSingRequired(String ru)
    {
        return RU_TO_EN_SING.get(ru);
    } 
}
