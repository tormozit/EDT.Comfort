package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Нечёткое сравнение слов: расстояние Левенштейна ({@link #editDistance}) и замена внешней
 * компоненты ИР {@code AddIn.FuzzySearch.StringSearch} ({@link #stringSearch}) — процент
 * схожести по расстоянию с порогами для коротких и длинных слов. Исходников компоненты нет,
 * поэтому совпадение с ИР не гарантировано: {@link BslExpectedTypePremiums} сверяет результат
 * с ИР, когда оно подключено.
 */
final class FuzzyStringSearch
{
    private FuzzyStringSearch()
    {
    }

    /**
     * Расстояние Левенштейна (вставка/удаление/замена символа) между a и b, не более max + 1 -
     * при превышении max точное значение не считается, дальше нужно только "больше max".
     */
    static int editDistance(String a, String b, int max)
    {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > max)
            return max + 1;
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++)
            prev[j] = j;
        for (int i = 1; i <= la; i++)
        {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++)
            {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lb];
    }

    /** Процент схожести без учёта регистра: {@code 100 · (1 − расстояние / длина длинного слова)}. */
    static int similarityPercent(String a, String b)
    {
        String left = a.toLowerCase(Locale.ROOT);
        String right = b.toLowerCase(Locale.ROOT);
        int longest = Math.max(left.length(), right.length());
        if (longest == 0)
            return 100;
        int distance = editDistance(left, right, longest);
        return (int)Math.round(100.0 * (longest - distance) / longest);
    }

    /**
     * Аналог {@code StringSearch(Строка, Кандидаты, Разделитель, МаксДлинаМалых, МинПроцентМалых,
     * МинПроцентБольших)}: индексы кандидатов, похожих на {@code search} не меньше порога. Порог малых
     * — для пары, где длинное слово не длиннее {@code maxSmallLength}. Порядок — от более похожих к
     * менее похожим, при равенстве — по индексу.
     */
    static List<Integer> stringSearch(String search, List<String> candidates, int maxSmallLength,
        int minPercentSmall, int minPercentBig)
    {
        List<int[]> found = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++)
        {
            String candidate = candidates.get(i);
            if (candidate == null || candidate.isEmpty())
                continue;
            int percent = similarityPercent(search, candidate);
            int threshold = Math.max(search.length(), candidate.length()) <= maxSmallLength
                ? minPercentSmall : minPercentBig;
            if (percent >= threshold)
                found.add(new int[] { i, percent });
        }
        found.sort((x, y) -> x[1] != y[1] ? Integer.compare(y[1], x[1]) : Integer.compare(x[0], y[0]));
        List<Integer> result = new ArrayList<>(found.size());
        for (int[] item : found)
            result.add(Integer.valueOf(item[0]));
        return result;
    }
}
