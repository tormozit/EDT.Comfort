package tormozit;

import java.util.Map;

/**
 * Перевод внутренних английских имён классов метаданных (BM/EMF, например {@code Catalog},
 * {@code CommonModule}) в русские — как они видны пользователю ({@code Справочник},
 * {@code ОбщийМодуль}). Нужен, чтобы секционный фильтр ({@link SmartMatcher#matchesTree})
 * мог сопоставлять русский текст фильтра с внутренним путём объекта (например,
 * {@code primary.getQualifiedName()} в {@link ComfortNavigatorSearchEngine} или
 * {@code description} в {@code OpenMdObjectItemsFilter}), у которого первый (а иногда и
 * промежуточные) сегмент — английское имя класса, а не отображаемое русское.
 */
public final class MdTypeNames
{
    private static final Map<String, String> EN_TO_RU = Map.ofEntries(
            Map.entry("Catalog", "Справочник"),
            Map.entry("Document", "Документ"),
            Map.entry("CommonModule", "ОбщийМодуль"),
            Map.entry("CommonCommand", "ОбщаяКоманда"),
            Map.entry("CommonPicture", "ОбщаяКартинка"),
            Map.entry("CommonForm", "ОбщаяФорма"),
            Map.entry("Constant", "Константа"),
            Map.entry("DataProcessor", "Обработка"),
            Map.entry("Report", "Отчет"),
            Map.entry("Enum", "Перечисление"),
            Map.entry("InformationRegister", "РегистрСведений"),
            Map.entry("AccumulationRegister", "РегистрНакопления"),
            Map.entry("AccountingRegister", "РегистрБухгалтерии"),
            Map.entry("CalculationRegister", "РегистрРасчета"),
            Map.entry("ScheduledJob", "РегламентноеЗадание"),
            Map.entry("EventSubscription", "ПодпискаНаСобытие"),
            Map.entry("SessionParameter", "ПараметрСеанса"),
            Map.entry("Role", "Роль"),
            Map.entry("WebService", "WebСервис"),
            Map.entry("Template", "Макет"),
            Map.entry("ChartOfCharacteristicTypes", "ПланВидовХарактеристик"),
            Map.entry("ChartOfAccounts", "ПланСчетов"),
            Map.entry("ChartOfCalculationTypes", "ПланВидовРасчета"),
            Map.entry("Attribute", "Реквизит"),
            Map.entry("TabularSection", "ТабличнаяЧасть"),
            Map.entry("Form", "Форма"),
            Map.entry("Command", "Команда"),
            Map.entry("Module", "Модуль"),
            Map.entry("EnumValue", "ЗначениеПеречисления"),
            Map.entry("Resource", "Ресурс"),
            Map.entry("Dimension", "Измерение"),
            Map.entry("Register", "Регистр"),
            Map.entry("Sequence", "Последовательность"),
            Map.entry("ExchangePlan", "ПланОбмена"),
            Map.entry("BusinessProcess", "БизнесПроцесс"),
            Map.entry("Task", "Задача"),
            Map.entry("ExternalDataSource", "ВнешнийИсточникДанных"),
            Map.entry("FilterCriterion", "КритерийОтбора"),
            Map.entry("FunctionalOption", "ФункциональнаяОпция"),
            Map.entry("FunctionalOptionsParameter", "ПараметрФункциональныхОпций"),
            Map.entry("JournalRegister", "ЖурналРегистрации"),
            Map.entry("SettingsStorage", "ХранилищеНастроек"),
            Map.entry("Subsystem", "Подсистема"),
            Map.entry("StyleItem", "ЭлементСтиля")
    );

    private MdTypeNames() {}

    /** Русское имя класса, если известно; иначе — исходная строка без изменений. */
    public static String ru(String enOrRu)
    {
        if (enOrRu == null)
            return null;
        String ru = EN_TO_RU.get(enOrRu);
        return ru != null ? ru : enOrRu;
    }

    /** Переводит каждый {@code '.'}-сегмент строки по отдельности; сегменты вне словаря не трогает. */
    public static String translateDottedToRu(String dotted)
    {
        if (dotted == null || dotted.isEmpty())
            return dotted;
        StringBuilder result = new StringBuilder();
        for (String part : dotted.split("\\.")) {
            if (result.length() > 0) result.append('.');
            result.append(ru(part));
        }
        return result.toString();
    }
}
