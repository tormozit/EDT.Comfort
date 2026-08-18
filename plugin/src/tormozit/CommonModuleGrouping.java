package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Динамическая группировка общих модулей по имени: семейство с общей базой и комбинируемым
 * хвостом из двух наборов суффиксов сворачивается в одну группу.
 *
 * <p>Хвост имени — любая комбинация элементов набора 1 и не более одного элемента набора 2
 * в любой позиции (до / между / после элементов набора 1). Оба набора необязательны.
 * Первое слово имени — база и не снимается, даже если совпадает с суффиксом
 * ({@code Служебный} / {@code СлужебныйКлиент} / {@code СлужебныйВызовСервера}).
 * Пример: {@code ВариантыОтветов}, {@code …Клиент}, {@code …КлиентСервер},
 * {@code …СлужебныйКлиент}, {@code …КлиентПереопределяемыйСервер}.
 *
 * <p>Чисто вычислительная логика без зависимостей на Eclipse UI/preferences. Источник суффиксов
 * и включения — {@link ComfortSettings#isGroupCommonModulesEnabled()} /
 * {@link ComfortSettings#getGroupCommonModulesSuffixes1()} /
 * {@link ComfortSettings#getGroupCommonModulesSuffixes2()}.
 */
public final class CommonModuleGrouping
{
    private CommonModuleGrouping() {}

    /**
     * Отрезает от {@code name} комбинируемый хвост из наборов {@code set1}/{@code set2}.
     * Токены снимаются справа налево (каждый раз самый длинный подходящий); из набора 2
     * допускается не более одного. Последний оставшийся токен не снимается: первое слово
     * имени — база, даже если оно совпадает с суффиксом.
     *
     * @return базовое имя, либо {@code name} без изменений, если хвост не распознан
     */
    public static String stripCombinedSuffixes(String name, List<String> set1, List<String> set2)
    {
        if (name == null || name.isEmpty())
            return name;
        boolean hasSet1 = set1 != null && !set1.isEmpty();
        boolean hasSet2 = set2 != null && !set2.isEmpty();
        if (!hasSet1 && !hasSet2)
            return name;

        int end = name.length();
        boolean usedSet2 = false;
        boolean strippedAny = false;

        while (end > 0)
        {
            String from1 = hasSet1 ? longestEnding(name, end, set1) : null;
            String from2 = !usedSet2 && hasSet2 ? longestEnding(name, end, set2) : null;

            String chosen = null;
            boolean chosenSet2 = false;
            if (from1 != null && from2 != null)
            {
                if (from2.length() > from1.length())
                {
                    chosen = from2;
                    chosenSet2 = true;
                }
                else
                    chosen = from1;
            }
            else if (from1 != null)
                chosen = from1;
            else if (from2 != null)
            {
                chosen = from2;
                chosenSet2 = true;
            }

            if (chosen == null)
                break;
            int nextEnd = end - chosen.length();
            // Первое слово имени — база, не суффикс: не снимать токен, после которого
            // имя стало бы пустым (иначе СлужебныйВызовСервера не сгруппируется).
            if (nextEnd == 0)
                break;
            end = nextEnd;
            strippedAny = true;
            if (chosenSet2)
                usedSet2 = true;
        }

        if (!strippedAny)
            return name;
        return name.substring(0, end);
    }

    /**
     * Группирует общие модули по базовому имени (см. {@link #stripCombinedSuffixes}).
     * В результат попадают только группы с двумя и более участниками — модуль без
     * «братьев» по базовому имени остаётся вне группировки.
     *
     * @return {@link LinkedHashMap} базовое имя → участники, в порядке первого появления
     */
    public static Map<String, List<CommonModule>> groupBySuffix(
            List<CommonModule> modules, List<String> set1, List<String> set2)
    {
        Map<String, List<CommonModule>> byBaseName = new LinkedHashMap<>();
        if (modules == null || modules.isEmpty())
            return byBaseName;

        for (CommonModule module : modules)
        {
            String name = module.getName();
            if (name == null || name.isEmpty())
                continue;
            String baseName = stripCombinedSuffixes(name, set1, set2);
            byBaseName.computeIfAbsent(baseName, k -> new ArrayList<>()).add(module);
        }

        byBaseName.values().removeIf(members -> members.size() < 2);
        return byBaseName;
    }

    /** Самый длинный токен из {@code tokens}, которым оканчивается {@code name[0..end)}. */
    private static String longestEnding(String name, int end, List<String> tokens)
    {
        String best = null;
        for (String token : tokens)
        {
            if (token == null || token.isEmpty())
                continue;
            int len = token.length();
            if (len > end)
                continue;
            if (!name.regionMatches(end - len, token, 0, len))
                continue;
            if (best == null || len > best.length())
                best = token;
        }
        return best;
    }
}
