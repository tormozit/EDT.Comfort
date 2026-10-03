package tormozit;

import java.util.ArrayList;
import java.util.List;

public class SmartMatcher {
    /** Союзы, пропуск которых между совпавшими словами не штрафуется премией фильтра (#429). */
    static final java.util.Set<String> UNPENALIZED_GAP_WORDS = java.util.Set.of("и", "или");

    /**
     * Спецсимвол привязки к началу текста (#659): {@code \вал} подходит только тексту, который
     * начинается с «вал». Действует в начале фрагмента вне кавычек, в т.ч. перед открывающей
     * кавычкой ({@code \"их вал"}); в середине слова и внутри кавычек — обычный символ.
     */
    public static final char ANCHOR_START = '\\';

    /** Фрагмент фильтра: искомый текст (без спецсимвола) и признак привязки к началу текста. */
    private record Frag(String text, boolean anchored) {
        /** {@code lowerText} — уже в нижнем регистре. */
        boolean foundIn(String lowerText) {
            return anchored ? lowerText.startsWith(text) : lowerText.contains(text);
        }
    }

    private final Frag[] fragments;
    private final List<List<Frag>> sections;
    public final String fullPattern;
    /** Текст фильтра для расчёта премии: без спецсимволов привязки. */
    private final String premiumPattern;
    public final boolean isEmpty;

    public SmartMatcher(String filterPattern) {
        if (filterPattern == null || filterPattern.trim().isEmpty()) {
            this.fragments = new Frag[0];
            this.sections = new ArrayList<>();
            this.fullPattern = "";
            this.premiumPattern = "";
            this.isEmpty = true;
        } else {
            this.fullPattern = filterPattern.toLowerCase().trim();
            this.sections = parse(this.fullPattern, true);
            // Плоские фрагменты (многословный фильтр) — отдельный разбор: кавычки учитываются
            // ("их вал" — один фрагмент с пробелом внутри), а точка НЕ разделитель — она часть
            // слова («объект.контрагент» ищется как есть). Точка режет только секции
            // (иерархический фильтр, matchesTree*).
            List<Frag> flat = new ArrayList<>();
            for (List<Frag> section : parse(this.fullPattern, false))
                flat.addAll(section);
            this.fragments = flat.toArray(new Frag[0]);
            this.premiumPattern = hasAnchored(flat) ? String.join(" ", texts(flat)) : this.fullPattern; //$NON-NLS-1$
            this.isEmpty = false;
        }
    }

    private static boolean hasAnchored(List<Frag> frags) {
        for (Frag frag : frags) {
            if (frag.anchored())
                return true;
        }
        return false;
    }

    private static List<String> texts(List<Frag> frags) {
        List<String> result = new ArrayList<>(frags.size());
        for (Frag frag : frags)
            result.add(frag.text());
        return result;
    }

    /** Тексты фрагментов плоского фильтра — без спецсимвола привязки к началу текста. */
    public String[] getFragments()
    {
        return texts(java.util.Arrays.asList(fragments)).toArray(new String[0]);
    }

    public boolean matches(String text) {
        if (isEmpty) return true;
        if (text == null) return false;

        String lowerText = text.toLowerCase();
        for (Frag frag : fragments) {
            if (!frag.foundIn(lowerText)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Парсинг текста фильтра в секции.
     * Вне кавычек: точка — разделитель секций (при {@code dotSplits}; иначе обычный символ и
     * секция одна), пробел — разделитель фрагментов внутри секции.
     * Внутри кавычек: пробелы и точки — часть фрагмента (кавычки удаляются).
     * Пустая секция (точка в начале/конце или две точки подряд) сохраняется и означает,
     * что подходят любые значения этого уровня: {@code ком.} — предпоследний уровень содержит «ком», последний любой.
     * {@link #ANCHOR_START} в начале фрагмента — привязка к началу текста (для секции — к началу
     * имени её уровня).
     *
     * Примеры:
     *   "док.реал тов"      → [[док], [реал, тов]]
     *   "док.реал" тов       → [[док.реал, тов]]
     *   док."реал тов"       → [[док], [реал тов]]
     *   ком.                 → [[ком], []]
     *   \док.\реал           → [[^док], [^реал]]
     */
    private static List<List<Frag>> parse(String filterText, boolean dotSplits) {
        List<List<Frag>> result = new ArrayList<>();
        if (filterText == null || filterText.isEmpty())
            return result;

        List<Frag> section = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inQuotes = false;
        boolean quoteAnchored = false;
        boolean hasDot = false;

        for (int i = 0; i < filterText.length(); i++) {
            char c = filterText.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (word.length() > 0)
                        section.add(new Frag(word.toString(), quoteAnchored));
                    word.setLength(0);
                    inQuotes = false;
                } else {
                    word.append(c);
                }
            } else if (c == '"') {
                // Одиночный спецсимвол прямо перед кавычкой привязывает фрагмент в кавычках.
                quoteAnchored = word.length() == 1 && word.charAt(0) == ANCHOR_START;
                if (!quoteAnchored)
                    addFragment(section, word.toString());
                word.setLength(0);
                inQuotes = true;
            } else if (dotSplits && c == '.') {
                addFragment(section, word.toString());
                word.setLength(0);
                result.add(section);
                section = new ArrayList<>();
                hasDot = true;
            } else if (Character.isWhitespace(c)) {
                addFragment(section, word.toString());
                word.setLength(0);
            } else {
                word.append(c);
            }
        }

        if (inQuotes) {
            if (word.length() > 0)
                section.add(new Frag(word.toString(), quoteAnchored));
        } else {
            addFragment(section, word.toString());
        }
        if (hasDot || !section.isEmpty())
            result.add(section);
        return result;
    }

    /** Слово вне кавычек: ведущий {@link #ANCHOR_START} — привязка, а не часть искомого текста. */
    private static void addFragment(List<Frag> section, String word) {
        String frag = word.trim();
        boolean anchored = !frag.isEmpty() && frag.charAt(0) == ANCHOR_START;
        if (anchored)
            frag = frag.substring(1);
        if (!frag.isEmpty())
            section.add(new Frag(frag, anchored));
    }

    public boolean hasMultipleSections() {
        return sections.size() > 1;
    }

    /**
     * Посекционное сопоставление: имя элемента делится по {@code .} на секции,
     * секции фильтра выравниваются с конца, и в каждой проверяются все фрагменты.
     * Если в фильтре одна секция — откат к {@link #matches(String)}.
     */
    public boolean matchesTree(String elementFullName) {
        if (isEmpty)
            return true;
        if (elementFullName == null)
            return false;
        if (sections.size() <= 1) {
            int lastDot = elementFullName.lastIndexOf('.');
            String elemOnly = lastDot >= 0 ? elementFullName.substring(lastDot + 1) : elementFullName;
            return matches(elemOnly);
        }

        String[] elemSections = elementFullName.toLowerCase().split("\\.");
        int filterCount = sections.size();
        int elemCount = elemSections.length;

        if (filterCount > elemCount) {
            return false;
        }

        int offset = elemCount - filterCount;
        for (int i = 0; i < filterCount; i++) {
            String elemSection = elemSections[offset + i];
            for (Frag frag : sections.get(i)) {
                if (!frag.foundIn(elemSection)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Как {@link #matchesTree}, но каждая секция фильтра должна совпасть с
     * секцией имени целиком ({@code Реквизит.Банк} не совпадёт с {@code БанкПолучателя}).
     */
    public boolean matchesTreeExact(String elementFullName)
    {
        if (isEmpty)
            return true;
        if (elementFullName == null)
            return false;
        String[] elemSections = elementFullName.toLowerCase().split("\\."); //$NON-NLS-1$
        int filterCount = sections.size();
        if (filterCount <= 1)
        {
            String elemOnly = elemSections.length == 0
                ? "" //$NON-NLS-1$
                : elemSections[elemSections.length - 1];
            return sectionEquals(elemOnly, filterCount == 0 ? List.of() : sections.get(0));
        }
        if (filterCount > elemSections.length)
            return false;
        int offset = elemSections.length - filterCount;
        for (int i = 0; i < filterCount; i++)
        {
            List<Frag> frags = sections.get(i);
            // Пустая секция фильтра (ком.) — подходят любые значения этого уровня.
            if (!frags.isEmpty() && !sectionEquals(elemSections[offset + i], frags))
                return false;
        }
        return true;
    }

    private static boolean sectionEquals(String elemSection, List<Frag> frags)
    {
        if (frags == null || frags.isEmpty())
            return elemSection == null || elemSection.isEmpty();
        return elemSection.equals(String.join(" ", texts(frags))); //$NON-NLS-1$
    }

    /**
     * Как {@link #matchesTree}, но секции пути уже разделены по узлам дерева
     * (имя файла {@code RecordSetModule.bsl} — одна секция, точка расширения не режет путь).
     * Хвостовые секции фильтра, которые все входят в имя последнего узла, относятся к нему;
     * оставшиеся выравниваются с родителями с конца.
     */
    public boolean matchesTreeParts(List<String> parts)
    {
        if (isEmpty)
            return true;
        if (parts == null || parts.isEmpty())
            return false;
        if (sections.size() <= 1)
            return matches(parts.get(parts.size() - 1));

        String lastPart = parts.get(parts.size() - 1).toLowerCase();
        int filterCount = sections.size();
        for (int eaten = filterCount; eaten >= 1; eaten--)
        {
            if (!sectionRangeInText(lastPart, filterCount - eaten, filterCount))
                continue;
            // Пустая секция (ком.) — отдельный уровень с любым значением: в имя узла её можно отнести,
            // только если в имени хватает сегментов через точку.
            if (eaten > 1 && hasEmptySection(filterCount - eaten, filterCount)
                && lastPart.split("\\.", -1).length < eaten) //$NON-NLS-1$
                continue;
            int remaining = filterCount - eaten;
            if (remaining == 0)
                return true;
            if (matchPrefixSectionsFromEnd(parts.subList(0, parts.size() - 1), remaining))
                return true;
        }
        return false;
    }

    private boolean sectionRangeInText(String lowerText, int fromSection, int toSection)
    {
        for (int i = fromSection; i < toSection; i++)
        {
            for (Frag frag : sections.get(i))
            {
                // Имя узла здесь несёт несколько секций сразу — привязанный фрагмент подходит
                // началу имени или началу любого его сегмента после точки.
                boolean found = frag.anchored()
                    ? lowerText.startsWith(frag.text()) || lowerText.contains("." + frag.text()) //$NON-NLS-1$
                    : lowerText.contains(frag.text());
                if (!found)
                    return false;
            }
        }
        return true;
    }

    private boolean hasEmptySection(int fromSection, int toSection)
    {
        for (int i = fromSection; i < toSection; i++)
        {
            if (sections.get(i).isEmpty())
                return true;
        }
        return false;
    }

    private boolean matchPrefixSectionsFromEnd(List<String> parts, int filterCount)
    {
        if (filterCount > parts.size())
            return false;
        int offset = parts.size() - filterCount;
        for (int i = 0; i < filterCount; i++)
        {
            String elemSection = parts.get(offset + i).toLowerCase();
            for (Frag frag : sections.get(i))
            {
                if (!frag.foundIn(elemSection))
                    return false;
            }
        }
        return true;
    }

    /** Фрагменты, которые есть в {@code text}, но отсутствуют в {@code other}. */
    public List<String> fragmentsInNotIn(String text, String other)
    {
        List<String> result = new ArrayList<>();
        if (isEmpty || text == null)
            return result;
        String lowerText = text.toLowerCase();
        String lowerOther = other != null ? other.toLowerCase() : ""; //$NON-NLS-1$
        for (Frag frag : fragments)
        {
            if (frag.foundIn(lowerText) && !frag.foundIn(lowerOther))
                result.add(frag.text());
        }
        return result;
    }

    /**
     * Разделяет сигнатуру на [0] ИмяМетода и [1] ПараметрыМетода
     */
    private String[] splitNameAndParams(String text) {
        if (text == null) return new String[]{"", ""};
        int parenIdx = text.indexOf('(');
        if (parenIdx >= 0) {
            return new String[] {
                text.substring(0, parenIdx).trim(),
                text.substring(parenIdx).trim()
            };
        } else {
            return new String[] { text.trim(), "" };
        }
    }

    public int computeNamePremium(String text) {
        String[] parts = splitNameAndParams(text);
        return computePartPremium(parts[0]);
    }

    public int computeParamPremium(String text) {
        String[] parts = splitNameAndParams(text);
        return computePartPremium(parts[1]);
    }

    /**
     * Расчет Премии Фильтра для изолированной части строки (Имени или Параметров)
     */
    private int computePartPremium(String partText) {
        return computePartPremium(partText, premiumPattern, getFragments());
    }

    /** Премии секций иерархического фильтра в порядке секций запроса. */
    public int[] computeTreePremiums(String elementFullName) {
        if (isEmpty || elementFullName == null)
            return new int[0];
        if (!hasMultipleSections()) {
            int lastDot = elementFullName.lastIndexOf('.');
            return new int[] { computeNamePremium(elementFullName.substring(lastDot + 1)) };
        }

        int[] premiums = new int[sections.size()];
        String[] elementSections = elementFullName.split("\\.", -1); //$NON-NLS-1$
        int offset = elementSections.length - sections.size();
        if (offset < 0)
            return premiums;
        for (int i = 0; i < sections.size(); i++) {
            List<String> section = texts(sections.get(i));
            if (!section.isEmpty())
                premiums[i] = computePartPremium(elementSections[offset + i],
                        String.join(" ", section), section.toArray(new String[0])); //$NON-NLS-1$
        }
        return premiums;
    }

    /** Сравнение премий по секциям слева направо, каждая по убыванию. */
    public int compareTreePremiums(String first, String second) {
        int[] p1 = computeTreePremiums(first);
        int[] p2 = computeTreePremiums(second);
        for (int i = 0; i < Math.min(p1.length, p2.length); i++) {
            int comparison = Integer.compare(p2[i], p1[i]);
            if (comparison != 0)
                return comparison;
        }
        return 0;
    }

    private int computePartPremium(String partText, String pattern, String[] fragments) {
        if (isEmpty || partText == null || partText.isEmpty() || pattern.isEmpty() || fragments.length == 0) {
            return 0;
        }

        String lowerText = partText.toLowerCase();

        // --- ГРУППА 1: ПОЛНОЕ СОВПАДЕНИЕ ВСЕГО ФИЛЬТРА ЦЕЛИКОМ ВНУТРИ ЧАСТИ ---
        if (lowerText.contains(pattern)) {
            int fullIdx = lowerText.indexOf(pattern);
            if (fullIdx == 0) {
                return 4; // полное совпадение в начале первого слова
            }
            if (isWordBoundary(partText, fullIdx)) {
                return 3; // полное совпадение в начале любого слова
            }
            
            boolean crossesWords = false;
            for (int i = fullIdx; i < fullIdx + pattern.length(); i++) {
                if (i > 0 && isWordBoundary(partText, i) && !Character.isUpperCase(partText.charAt(i))) {
                    crossesWords = true;
                    break;
                }
            }
            if (crossesWords) {
                return 1; // полное совпадение с пересечением слов
            } else {
                return 2; // полное совпадение внутри любого слова
            }
        }

        // --- ГРУППА 2: СОВПАДЕНИЕ ПО ОТДЕЛЬНЫМ ТОКЕНАМ ---
        // Собираем фрагменты, которые присутствуют в тексте
        List<String> presentFragments = new ArrayList<>();
        for (String frag : fragments) {
            if (lowerText.contains(frag)) {
                presentFragments.add(frag);
            }
        }
        // Если не все фрагменты запроса присутствуют в данной части — премия 0
        if (presentFragments.size() != fragments.length) {
            return 0;
        }
        // Все фрагменты присутствуют. Теперь проверяем, совпадают ли они с началами слов.
        List<Integer> wordBoundaries = new ArrayList<>();
        wordBoundaries.add(0);
        for (int i = 1; i < partText.length(); i++) {
            if (isWordBoundary(partText, i) || isConjunctionITail(partText, i)) {
                wordBoundaries.add(i);
            }
        }
        int matchedWordsCount = 0;
        for (String frag : fragments) {
            for (int w = 0; w < wordBoundaries.size(); w++) {
                int boundaryPos = wordBoundaries.get(w);
                if (lowerText.startsWith(frag, boundaryPos)) {
                    matchedWordsCount++;
                    break;
                }
            }
        }
        if (matchedWordsCount == fragments.length) {
            // Находим индекс слова для каждого фрагмента фильтра
            int[] matchedWordIdxs = new int[fragments.length];
            for (int fi = 0; fi < fragments.length; fi++) {
                matchedWordIdxs[fi] = -1;
                for (int w = 0; w < wordBoundaries.size(); w++) {
                    if (lowerText.startsWith(fragments[fi], wordBoundaries.get(w))) {
                        matchedWordIdxs[fi] = w;
                        break;
                    }
                }
            }

            // Мин/макс индексы совпавших слов (независимо от порядка фрагментов)
            int minWordIdx = matchedWordIdxs[0];
            int maxWordIdx = matchedWordIdxs[0];
            for (int idx : matchedWordIdxs) {
                if (idx < minWordIdx) minWordIdx = idx;
                if (idx > maxWordIdx) maxWordIdx = idx;
            }

            // БазоваяПремия: определяем по минимальному индексу совпавшего слова.
            // Промежуточные несовпавшие слова — «пропуск» (штраф), кроме союзов «и»/«или» (#429).
            java.util.Set<Integer> matchedSet = new java.util.HashSet<>();
            for (int idx : matchedWordIdxs) matchedSet.add(idx);
            boolean noGaps = true;
            for (int w = minWordIdx + 1; w < maxWordIdx; w++) {
                if (matchedSet.contains(w))
                    continue;
                if (!UNPENALIZED_GAP_WORDS.contains(wordAt(partText, wordBoundaries, w))) {
                    noGaps = false;
                    break;
                }
            }
            int basePremium;
            if (minWordIdx == 0) {
                basePremium = noGaps ? 3 : 2;
            } else {
                basePremium = 1;
            }

            // Порядок сохранён, если индексы совпавших слов идут в том же порядке, что фрагменты в фильтре
            boolean orderKept = true;
            for (int fi = 1; fi < fragments.length; fi++) {
                if (matchedWordIdxs[fi] <= matchedWordIdxs[fi - 1]) {
                    orderKept = false;
                    break;
                }
            }

            // ПремияФильтра = БазоваяПремия * 2 - (порядок нарушен ? 1 : 0)
            return basePremium * 2 - (orderKept ? 0 : 1);
        } else {
            // Все фрагменты есть, но не все совпали с началами слов — совпадение внутри слов
            return 1;
        }
    }

    /** Все фрагменты из всех секций одним плоским списком (для подсветки/поиска без учёта иерархии). */
    public List<String> getAllSectionFragments() {
        return texts(allSectionFrags());
    }

    private List<Frag> allSectionFrags() {
        List<Frag> all = new ArrayList<>();
        for (List<Frag> sec : sections) {
            all.addAll(sec);
        }
        return all;
    }

    /**
     * Подсветка многословного (плоского) фильтра — ровно те фрагменты, по которым работает
     * {@link #matches}: точка внутри слова — его часть.
     */
    public List<HighlightRange> getHighlightRanges(String text) {
        return highlightFragments(text, java.util.Arrays.asList(fragments));
    }

    /**
     * Подсветка иерархического фильтра, когда строка несёт лишь часть секций (родитель/потомок):
     * фрагменты всех секций по отдельности, точка — разделитель секций.
     */
    public List<HighlightRange> getSectionHighlightRanges(String text) {
        return highlightFragments(text, allSectionFrags());
    }

    private List<HighlightRange> highlightFragments(String text, List<Frag> frags) {
        List<HighlightRange> ranges = new ArrayList<>();
        if (isEmpty || text == null) return ranges;

        appendFragmentRanges(ranges, text, 0, frags);
        return ranges;
    }

    public List<HighlightRange> getLastSectionHighlightRanges(String text) {
        List<HighlightRange> ranges = new ArrayList<>();
        if (isEmpty || text == null || sections.isEmpty()) return ranges;

        appendFragmentRanges(ranges, text, 0, sections.get(sections.size() - 1));
        return ranges;
    }

    /**
     * Подсветка для {@link #matchesTree}: фрагменты ищутся только в тех секциях текста
     * (по {@code .}), которые выравниваются с секциями фильтра с конца. Родительские секции
     * без пары в фильтре не красятся.
     */
    public List<HighlightRange> getTreeHighlightRanges(String text)
    {
        List<HighlightRange> ranges = new ArrayList<>();
        if (isEmpty || text == null || text.isEmpty())
            return ranges;

        if (sections.size() <= 1)
        {
            int lastDot = text.lastIndexOf('.');
            String section = lastDot >= 0 ? text.substring(lastDot + 1) : text;
            int sectionStart = lastDot >= 0 ? lastDot + 1 : 0;
            appendFragmentRanges(ranges, section, sectionStart, java.util.Arrays.asList(fragments));
            return ranges;
        }

        String[] segments = text.split("\\.", -1); //$NON-NLS-1$
        int filterCount = sections.size();
        int segCount = segments.length;
        if (filterCount > segCount)
            return ranges;

        int[] segmentStart = new int[segCount];
        int pos = 0;
        for (int i = 0; i < segCount; i++)
        {
            segmentStart[i] = pos;
            pos += segments[i].length();
            if (i < segCount - 1)
                pos++;
        }

        int offset = segCount - filterCount;
        for (int i = 0; i < filterCount; i++)
        {
            String segment = segments[offset + i];
            appendFragmentRanges(ranges, segment, segmentStart[offset + i], sections.get(i));
        }
        return ranges;
    }

    private static void appendFragmentRanges(List<HighlightRange> ranges, String segment,
            int segmentStart, List<Frag> frags)
    {
        if (segment == null || frags == null)
            return;
        String lowerSegment = segment.toLowerCase();
        for (Frag frag : frags)
        {
            String fragText = frag.text();
            if (frag.anchored())
            {
                // Привязанный фрагмент красится только в начале текста.
                if (lowerSegment.startsWith(fragText))
                    ranges.add(new HighlightRange(segmentStart, fragText.length()));
                continue;
            }
            int idx = lowerSegment.indexOf(fragText);
            while (idx >= 0)
            {
                ranges.add(new HighlightRange(segmentStart + idx, fragText.length()));
                idx = lowerSegment.indexOf(fragText, idx + fragText.length());
            }
        }
    }

    /** Текст слова (буквы/цифры, в нижнем регистре) по индексу его границы в {@code wordBoundaries}. */
    private static String wordAt(String text, List<Integer> boundaries, int wordIdx) {
        int start = boundaries.get(wordIdx);
        int end = wordIdx + 1 < boundaries.size() ? boundaries.get(wordIdx + 1) : text.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c))
                sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * Позиция сразу за одиночным союзом «И», склеенным с началом следующего слова
     * ({@code ...дИР...} в {@code ПриходИРасход}) — естественной границы слова тут нет
     * (заглавная за заглавной), но «И» нужно считать отдельным словом (#429).
     */
    static boolean isConjunctionITail(String text, int index) {
        if (index < 2 || index >= text.length())
            return false;
        return text.charAt(index - 1) == 'И'
            && Character.isUpperCase(text.charAt(index))
            && Character.isLowerCase(text.charAt(index - 2));
    }

    public boolean isWordBoundary(String originalText, int index) {
        if (index <= 0) return true;
        char prev = originalText.charAt(index - 1);
        char curr = originalText.charAt(index);
        return !Character.isLetterOrDigit(prev) || 
               (Character.isLowerCase(prev) && Character.isUpperCase(curr));
    }

    public static class HighlightRange {
        public final int offset;
        public final int length;
        public HighlightRange(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }
}
