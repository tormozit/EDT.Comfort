package tormozit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Динамическая группировка общих модулей по имени: семейство «БазовоеИмя» + известные
 * суффиксы (Клиент/Сервер/КлиентСервер/ПовтИсп/…) сворачивается в одну группу.
 *
 * <p>Чисто вычислительная логика без зависимостей на Eclipse UI/preferences — пересчитывается
 * заново при каждом обращении, ничего не хранится. Источник суффиксов и включения —
 * {@link ComfortSettings#isGroupCommonModulesEnabled()} / {@link ComfortSettings#getGroupCommonModulesSuffixes()}.
 */
public final class CommonModuleGrouping
{
    private CommonModuleGrouping() {}

    /**
     * Отрезает от {@code name} самый длинный подходящий суффикс из {@code suffixes}.
     * Суффикс не отрезается, если после этого база станет пустой.
     *
     * @param suffixes список суффиксов в произвольном порядке (метод сам ищет самый длинный совпавший)
     * @return базовое имя (без суффикса), либо {@code name} без изменений, если ни один суффикс не подошёл
     */
    public static String stripLongestSuffix(String name, List<String> suffixes)
    {
        if (name == null || name.isEmpty() || suffixes == null || suffixes.isEmpty())
            return name;

        String best = null;
        for (String suffix : suffixes)
        {
            if (suffix == null || suffix.isEmpty())
                continue;
            if (name.length() <= suffix.length())
                continue;
            if (!name.endsWith(suffix))
                continue;
            if (best == null || suffix.length() > best.length())
                best = suffix;
        }
        return best == null ? name : name.substring(0, name.length() - best.length());
    }

    /**
     * Группирует общие модули по базовому имени (см. {@link #stripLongestSuffix}).
     * В результат попадают только группы с двумя и более участниками — модуль без
     * «братьев» по базовому имени остаётся вне группировки.
     *
     * @return {@link LinkedHashMap} базовое имя → участники, в порядке первого появления
     */
    public static Map<String, List<CommonModule>> groupBySuffix(List<CommonModule> modules, List<String> suffixes)
    {
        Map<String, List<CommonModule>> byBaseName = new LinkedHashMap<>();
        if (modules == null || modules.isEmpty())
            return byBaseName;

        List<String> sortedSuffixes = new ArrayList<>();
        if (suffixes != null)
            sortedSuffixes.addAll(suffixes);
        sortedSuffixes.sort(Comparator.comparingInt(String::length).reversed());

        for (CommonModule module : modules)
        {
            String name = module.getName();
            if (name == null || name.isEmpty())
                continue;
            String baseName = stripLongestSuffix(name, sortedSuffixes);
            byBaseName.computeIfAbsent(baseName, k -> new ArrayList<>()).add(module);
        }

        byBaseName.values().removeIf(members -> members.size() < 2);
        return byBaseName;
    }
}
