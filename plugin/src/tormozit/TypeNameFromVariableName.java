package tormozit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Порт {@code ирОбщий.ИмяТипаИзИмениПеременнойЛкс} (ИР, {@code CommonModules\ирОбщий}): имя типа
 * значения по имени переменной или реквизита. Потребители — подбор типа по имени реквизита
 * ({@link TypeByNameAdvisor}) и ожидаемый тип автодополнения ({@link BslExpectedTypePremiums}).
 *
 * <p>Морфология — только локальная ветка {@code КорниСловЛкс} ({@code УдалитьОкончаниеСловаЛкс}):
 * внешний сервис ИР по умолчанию тоже не зовёт. Всё, что ИР берёт из метаданных, передаёт
 * вызывающий ({@link Catalog}).
 */
final class TypeNameFromVariableName
{
    /** Строка {@code ТаблицаИменМетаТиповЛкс}: имя объекта и полное имя типа ({@code СправочникСсылка.Валюты}). */
    record MetaType(String word, String typeName)
    {
    }

    /**
     * Метаданные, которые ИР читает сам.
     *
     * @param metaTypes   {@code ТаблицаИменМетаТиповЛкс(ЛиСсылочные)}: аргумент — нужны ли ссылочные
     *                    типы (определяемые нужны всегда); {@code null} — подбора по метаданным нет
     * @param fieldType   {@code ПоляТаблицыБДЛкс}: тип поля таблицы ссылочного типа по имени поля;
     *                    {@code null} — уточнения нет
     * @param commonType  {@code ОписаниеОбщегоТипа}: имя платформенного типа, совпадающего с именем
     *                    переменной; {@code null} — не проверяется
     */
    record Catalog(Function<Boolean, List<MetaType>> metaTypes, BiFunction<String, String, String> fieldType,
        Function<String, String> commonType)
    {
        static final Catalog NONE = new Catalog(null, null, null);
    }

    private static final String DEFINED_TYPE_PREFIX = "ОпределяемыйТип."; //$NON-NLS-1$
    private static final String NUMBER_WORDS = ",вес,высота,длина,длительность,дней,доля,долг,доход,глубина,индекс," //$NON-NLS-1$
        + "итог,количество,коэффициент,кратность,курс,масса,макс,мин,наценка,оборот,объем,остаток,премия,приход," //$NON-NLS-1$
        + "процент,размер,разница,разность,расстояние,расход,скидка,стоимость,сумма,счетчик,цена,число,ширина,штраф,"; //$NON-NLS-1$
    /** Окончания {@code УдалитьОкончаниеСловаЛкс} — в порядке оригинала (с его повторами). */
    private static final String[] WORD_ENDINGS = { "овые", "овый", "овая", "ость", "ний", "ный", "ная", "ное", "ите", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
        "ить", "ишь", "ами", "ями", "ами", "им", "ит", "ат", "ят", "ов", "ев", "ин", "ын", "ах", "ях", "ую", "юю", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$ //$NON-NLS-12$ //$NON-NLS-13$ //$NON-NLS-14$ //$NON-NLS-15$ //$NON-NLS-16$ //$NON-NLS-17$
        "ей", "ой", "ий", "ый", "ая", "яя", "ое", "ее", "оё", "её", "ью", "ью", "ем", "ом", "им", "ых", "их", "ую", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$ //$NON-NLS-12$ //$NON-NLS-13$ //$NON-NLS-14$ //$NON-NLS-15$ //$NON-NLS-16$ //$NON-NLS-17$ //$NON-NLS-18$
        "юю", "а", "я", "о", "е", "ь", "и", "ы", "у", "ю", "й" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$

    private TypeNameFromVariableName()
    {
    }

    /** Как в ИР по умолчанию: подбор по метаданным, без платформенных типов, с пропуском слов. */
    static String resolve(String name, Catalog catalog)
    {
        return resolve(name, true, false, false, catalog);
    }

    /**
     * {@code ИмяТипаИзИмениПеременнойЛкс(ИмяПеременной, ПодбиратьМетаТип, , РазрешитьНехранимыеТипы,
     * ЗапретитьПропускСлов)}.
     *
     * @return имя типа или {@code null}, если подобрать не удалось
     */
    static String resolve(String name, boolean pickMetaType, boolean allowNonStoredTypes, boolean forbidWordSkip,
        Catalog catalog)
    {
        if (name == null || name.isBlank())
            return null;
        if (catalog == null)
            catalog = Catalog.NONE;
        String typeName = null;
        String nameLower = lower(name);
        String[] words = Global.representationFromIdentifier(name).split(" ", -1); //$NON-NLS-1$
        String firstWord = lower(words[0]);
        String lastWord = lower(words[words.length - 1]);
        String firstNonAdjective = firstWord;
        Integer nonAdjectiveIndex = Integer.valueOf(0);
        if (isAdjective(firstWord))
        {
            if (words.length > 1)
            {
                firstNonAdjective = lower(words[1]);
                nonAdjectiveIndex = Integer.valueOf(1);
            }
            else
            {
                firstNonAdjective = ""; //$NON-NLS-1$
                nonAdjectiveIndex = null;
            }
        }
        if (firstNonAdjective.equals("дата") //$NON-NLS-1$
            || lastWord.equals("года") //$NON-NLS-1$
            || lastWord.equals("дата") //$NON-NLS-1$
            || lastWord.equals("дня") //$NON-NLS-1$
            || lastWord.equals("месяца") //$NON-NLS-1$
            || lastWord.equals("периода")) //$NON-NLS-1$
            typeName = "Дата"; //$NON-NLS-1$
        else if (firstWord.equals("сз")) //$NON-NLS-1$
            typeName = "СписокЗначений"; //$NON-NLS-1$
        else if (firstWord.equals("тз")) //$NON-NLS-1$
            typeName = "ТаблицаЗначений"; //$NON-NLS-1$
        else if (firstWord.equals("дз")) //$NON-NLS-1$
            typeName = "ДеревоЗначений"; //$NON-NLS-1$
        else if (firstWord.equals("мс")) //$NON-NLS-1$
            typeName = "Массив"; //$NON-NLS-1$
        else if (firstWord.equals("стк")) //$NON-NLS-1$
            typeName = "Структура"; //$NON-NLS-1$
        else if (firstWord.equals("стт")) //$NON-NLS-1$
            typeName = "Соответствие"; //$NON-NLS-1$
        else if (firstWord.equals("объектмд")) //$NON-NLS-1$
            typeName = "ОбъектМетаданных"; //$NON-NLS-1$
        else
        {
            // Удаляем окончание
            if (lastWord.endsWith("а") || lastWord.endsWith("о")) //$NON-NLS-1$ //$NON-NLS-2$
                lastWord = lastWord.substring(0, lastWord.length() - 1);
            if (isBooleanName(nameLower, firstWord, lastWord))
                typeName = "Булево"; //$NON-NLS-1$
            else if (firstWord.length() > 4 && isVerb(firstWord))
                typeName = "Булево"; //$NON-NLS-1$
        }
        if (allowNonStoredTypes && isEmpty(typeName) && catalog.commonType() != null)
        {
            String common = catalog.commonType().apply(name);
            if (!isEmpty(common))
                typeName = common;
        }
        if (isEmpty(typeName)
            // сумма - вредно для СуммаВключаетНДС
            && (NUMBER_WORDS.contains("," + firstNonAdjective + ",") //$NON-NLS-1$ //$NON-NLS-2$
                || ",порядок,уровень,".contains("," + nameLower + ",") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || nameLower.startsWith("номерстроки"))) //$NON-NLS-1$
            typeName = "Число"; //$NON-NLS-1$
        if (pickMetaType && catalog.metaTypes() != null && (isEmpty(typeName) || "Число".equals(typeName)) //$NON-NLS-1$
            && !firstNonAdjective.equals("время")) //$NON-NLS-1$
            typeName = pickMetaType(name, words, typeName, firstNonAdjective, lastWord, nonAdjectiveIndex,
                forbidWordSkip, catalog);
        return isEmpty(typeName) ? null : typeName;
    }

    private static String pickMetaType(String name, String[] words, String typeName, String firstNonAdjective,
        String lastWord, Integer nonAdjectiveIndex, boolean forbidWordSkip, Catalog catalog)
    {
        final int minFilterLength = 4;
        List<MetaType> table = catalog.metaTypes().apply(Boolean.valueOf(!"Число".equals(typeName))); //$NON-NLS-1$
        if (table == null || table.isEmpty())
            return typeName;
        List<MetaType> sorted = new ArrayList<>(table);
        sorted.sort(Comparator.comparing(row -> lower(row.word())));
        String stems = wordStems(name);
        List<String> filters = new ArrayList<>();
        filters.add(Global.identifierFromRepresentation(stems));
        if (!forbidWordSkip)
        {
            if (words.length > 1 && nonAdjectiveIndex != null && nonAdjectiveIndex.intValue() > 0)
            {
                // Без первых прилагательных
                List<String> parts = new ArrayList<>(List.of(stems.split(" ", -1))); //$NON-NLS-1$
                for (int i = 0; i < nonAdjectiveIndex.intValue() && !parts.isEmpty(); i++)
                    parts.remove(0);
                filters.add(Global.identifierFromRepresentation(String.join(" ", parts))); //$NON-NLS-1$
            }
            if (words.length > 1 && (nonAdjectiveIndex == null || nonAdjectiveIndex.intValue() != 1)
                || words.length > 2)
            {
                // Без последнего слова
                List<String> parts = new ArrayList<>(List.of(stems.split(" ", -1))); //$NON-NLS-1$
                parts.remove(parts.size() - 1);
                filters.add(Global.identifierFromRepresentation(String.join(" ", parts))); //$NON-NLS-1$
            }
        }
        String metaTypeName = null;
        for (String filter : filters)
        {
            if (filter.length() < minFilterLength)
                continue;
            MetaType best = findBestWord(sorted, filter);
            if (best != null)
            {
                metaTypeName = best.typeName();
                break;
            }
        }
        boolean definedType = metaTypeName != null && metaTypeName.startsWith(DEFINED_TYPE_PREFIX);
        if (!isEmpty(metaTypeName) && (isEmpty(typeName) || "Число".equals(typeName) && definedType)) //$NON-NLS-1$
        {
            typeName = metaTypeName;
            if (!definedType && !firstNonAdjective.equals(lastWord) && catalog.fieldType() != null)
            {
                String fieldType = catalog.fieldType().apply(typeName, titleCase(lastWord));
                if (!isEmpty(fieldType))
                    typeName = fieldType;
            }
        }
        return typeName;
    }

    private static boolean isBooleanName(String nameLower, String firstWord, String lastWord)
    {
        switch (nameLower)
        {
            case "активность": //$NON-NLS-1$
            case "видимость": //$NON-NLS-1$
            case "доступность": //$NON-NLS-1$
            case "предопределенный": //$NON-NLS-1$
            case "стандартнаяобработка": //$NON-NLS-1$
            case "сторно": //$NON-NLS-1$
            case "толькопросмотр": //$NON-NLS-1$
                return true;
            default:
                break;
        }
        switch (firstWord)
        {
            case "есть": //$NON-NLS-1$
            case "ли": //$NON-NLS-1$
            case "наличие": //$NON-NLS-1$
            case "отказ": //$NON-NLS-1$
            case "пометка": //$NON-NLS-1$
            case "успех": //$NON-NLS-1$
            case "это": //$NON-NLS-1$
                return true;
            default:
                break;
        }
        switch (lastWord)
        {
            case "выполнен": //$NON-NLS-1$
            case "готов": //$NON-NLS-1$
            case "завершен": //$NON-NLS-1$
            case "обработан": //$NON-NLS-1$
            case "помечен": //$NON-NLS-1$
            case "проведен": //$NON-NLS-1$
            case "удален": //$NON-NLS-1$
                return true;
            default:
                return false;
        }
    }

    /** {@code ЛиГлаголЛкс}: проверяется только неопределённая форма. */
    static boolean isVerb(String word)
    {
        String lowered = lower(word);
        // "ить" вредно для УдалитьДатаОкончания
        return !lowered.equals("печать") //$NON-NLS-1$
            && (",ает,ать,еть,ить,оть,тся,уть,ыть,".contains("," + right(lowered, 3) + ",") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || ",ти,".contains("," + right(lowered, 2) + ",")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** {@code ЛиПрилагательноеЛкс}. */
    static boolean isAdjective(String word)
    {
        // Минимум 3 буквы кроме окончания
        return word.length() > 4
            && ",ой,ие,ые,ее,ое,ая,яя,ий,ый,".contains("," + lower(right(word, 2)) + ","); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Локальная ветка {@code КорниСловЛкс}: слова представления в нижнем регистре без окончаний, через пробел. */
    static String wordStems(String text)
    {
        String lowered = lower(Global.representationFromIdentifier(text == null ? "" : text.trim())); //$NON-NLS-1$
        List<String> stems = new ArrayList<>();
        for (String word : lowered.split(" ", -1)) //$NON-NLS-1$
            stems.add(removeWordEnding(word));
        return String.join(" ", stems); //$NON-NLS-1$
    }

    /** {@code УдалитьОкончаниеСловаЛкс}: первое подошедшее окончание, если после него остаётся не меньше 2 символов. */
    static String removeWordEnding(String word)
    {
        String lowered = lower(word);
        for (String ending : WORD_ENDINGS)
        {
            if (lowered.endsWith(ending) && lowered.length() - ending.length() >= 2)
                return lowered.substring(0, lowered.length() - ending.length());
        }
        return lowered;
    }

    /**
     * {@code НайтиСтрокуЛучшегоСловаЛкс}: строка с наибольшей премией фильтра (не меньше 3); при
     * равной премии 3 — та, что начинается с первых 4 символов фильтра. {@code table} отсортирована
     * по имени в нижнем регистре.
     */
    static MetaType findBestWord(List<MetaType> table, String filter)
    {
        MetaType best = null;
        int bestPremium = 0;
        String prefix = lower(left(filter, 4));
        for (MetaType row : table)
        {
            int premium = filterPremium(filter, row.word());
            // для случая выбора между ПараметрыПроцедурПланирования и ПроцедурыПланирования
            if (premium > bestPremium || premium == 3 && bestPremium == 3 && lower(row.word()).startsWith(prefix))
            {
                best = row;
                bestPremium = premium;
            }
        }
        return bestPremium < 3 ? null : best;
    }

    /**
     * {@code НайтиФильтрВСловеЛкс(Ложь, Фильтр, Слово, НРег(Слово))}: 0 — фильтр в слове не найден;
     * 4 — подстрока с начала; 3 — подстрока с начала подслова; 2 — внутри подслова; 1 — с
     * пересечением подслов. Без подстроки — посимвольно по началам подслов (1–3).
     */
    static int filterPremium(String filter, String word)
    {
        if (filter == null || filter.isEmpty())
            return 1;
        String nFilter = lower(filter);
        String nWord = lower(word);
        int filterLength = nFilter.length();
        int wordLength = word.length();
        int position = nWord.indexOf(nFilter) + 1;
        if (position > 0)
        {
            if (position == 1)
                return 4; // Полное совпадение в начале
            if (position == 2 && !Character.isLetter(word.charAt(0)))
                return 3; // Полное совпадение в начале после первой небуквы
            String firstChar = mid(word, position, 1);
            String fragment = mid(word, position, filterLength);
            if (upper(firstChar).equals(firstChar))
                return 3; // полное совпадение внутри с начала подслова
            if (lower(fragment).equals(fragment))
                return 2; // полное совпадение внутри подслова
            return 1; // полное совпадение с пересечением подслов
        }
        int premium = 1;
        int positionInWord = 1;
        boolean previousMatch = false;
        int wordExcess = wordLength - filterLength;
        int firstMatchPosition = 0;
        List<String> skippedFragments = new ArrayList<>();
        for (int positionInFilter = 1; positionInFilter <= filterLength; positionInFilter++)
        {
            String filterChar = mid(nFilter, positionInFilter, 1);
            String filterFragment = mid(nFilter, positionInFilter, 2);
            while (positionInWord <= wordLength)
            {
                String wordChar = mid(nWord, positionInWord, 1);
                if (!wordChar.equals(filterChar))
                {
                    if (previousMatch && premium == 1 && firstMatchPosition == 1)
                        premium = 2; // Частичное совпадение в начале
                    previousMatch = false;
                    String rest = mid(word, positionInWord + 1, Integer.MAX_VALUE);
                    int newPosition = rest.indexOf(titleCase(filterFragment)) + 1;
                    if (newPosition == 0 && !filterFragment.equals(filterChar))
                        newPosition = rest.indexOf(upper(filterChar)) + 1;
                    if (newPosition == 0)
                    {
                        positionInWord = wordLength + 1;
                        break;
                    }
                    skippedFragments.add(mid(word, positionInWord, newPosition));
                    positionInWord += newPosition;
                }
                boolean charFits = previousMatch || positionInWord == 1;
                if (!charFits)
                {
                    String symbol = mid(word, positionInWord, 1);
                    charFits = upper(symbol).equals(symbol);
                }
                if (charFits)
                {
                    previousMatch = true;
                    if (firstMatchPosition == 0)
                        firstMatchPosition = positionInWord;
                    break;
                }
                positionInWord++;
                previousMatch = false;
                if (positionInWord > wordExcess + positionInFilter)
                    break;
            }
            if (positionInWord > wordExcess + positionInFilter)
            {
                if (firstMatchPosition == 0 || firstMatchPosition + 1 > wordExcess + positionInFilter)
                {
                    premium = 0;
                    break;
                }
                premium = 1;
                positionInFilter = 0;
                positionInWord = firstMatchPosition + 1;
                firstMatchPosition = 0;
            }
            positionInWord++;
        }
        if (premium > 1)
        {
            boolean skippedWordStarts = false;
            for (String skipped : skippedFragments)
            {
                if (!lower(skipped).equals(skipped))
                {
                    skippedWordStarts = true;
                    break;
                }
            }
            if (!skippedWordStarts)
                premium++;
        }
        return premium;
    }

    /** {@code Сред(s, позиция, длина)} с позицией от 1; за границами — пусто. */
    private static String mid(String s, int position, int length)
    {
        int start = Math.max(0, position - 1);
        if (start >= s.length() || length <= 0)
            return ""; //$NON-NLS-1$
        long end = Math.min((long)s.length(), (long)start + length);
        return s.substring(start, (int)end);
    }

    private static String left(String s, int length)
    {
        return s.length() <= length ? s : s.substring(0, length);
    }

    private static String right(String s, int length)
    {
        return s.length() <= length ? s : s.substring(s.length() - length);
    }

    /** {@code ТРег}: первая буква заглавная, остальные строчные. */
    private static String titleCase(String s)
    {
        if (s == null || s.isEmpty())
            return ""; //$NON-NLS-1$
        return upper(s.substring(0, 1)) + lower(s.substring(1));
    }

    private static String lower(String s)
    {
        return s == null ? "" : s.toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private static String upper(String s)
    {
        return s == null ? "" : s.toUpperCase(Locale.ROOT); //$NON-NLS-1$
    }

    private static boolean isEmpty(String s)
    {
        return s == null || s.isEmpty();
    }
}
