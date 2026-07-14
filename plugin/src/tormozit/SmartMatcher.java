package tormozit;

import java.util.ArrayList;
import java.util.List;

public class SmartMatcher {
    private final String[] fragments;
    private final List<List<String>> sections;
    public final String fullPattern;
    public final boolean isEmpty;

    public SmartMatcher(String filterPattern) {
        if (filterPattern == null || filterPattern.trim().isEmpty()) {
            this.fragments = new String[0];
            this.sections = new ArrayList<>();
            this.fullPattern = "";
            this.isEmpty = true;
        } else {
            this.fullPattern = filterPattern.toLowerCase().trim();
            this.sections = parseSections(this.fullPattern);
            // Фрагменты строим из уже распарсенных секций (учитывают кавычки: "их вал" — один
            // фрагмент с пробелом внутри), а не наивным split по пробелу — иначе кавычки остаются
            // приклеенными к словам (их / вал ) и matches() никогда не находит совпадение.
            this.fragments = flattenSections(this.sections);
            this.isEmpty = false;
        }
    }

    private static String[] flattenSections(List<List<String>> sections) {
        List<String> all = new ArrayList<>();
        for (List<String> sec : sections) {
            all.addAll(sec);
        }
        return all.toArray(new String[0]);
    }

    public String[] getFragments()
    {
        return fragments.clone();
    }

    public boolean matches(String text) {
        if (isEmpty) return true;
        if (text == null) return false;
        
        String lowerText = text.toLowerCase();
        for (String frag : fragments) {
            if (!lowerText.contains(frag)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Парсинг текста фильтра в секции.
     * Вне кавычек: точка — разделитель секций, пробел — разделитель фрагментов внутри секции.
     * Внутри кавычек: пробелы и точки — часть фрагмента (кавычки удаляются).
     *
     * Примеры:
     *   "док.реал тов"      → [[док], [реал, тов]]
     *   "док.реал" тов       → [[док.реал], [тов]]
     *   док."реал тов"       → [[док], [реал тов]]
     */
    public static List<List<String>> parseSections(String filterText) {
        List<List<String>> result = new ArrayList<>();
        if (filterText == null || filterText.isEmpty())
            return result;

        StringBuilder pending = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < filterText.length(); i++) {
            char c = filterText.charAt(i);
            if (c == '"') {
                if (inQuotes) {
                    String frag = pending.toString();
                    pending.setLength(0);
                    inQuotes = false;
                    if (!frag.isEmpty())
                        result.add(java.util.Collections.singletonList(frag));
                } else {
                    flushUnquotedToSections(pending, result);
                    pending.setLength(0);
                    inQuotes = true;
                }
            } else if (inQuotes) {
                pending.append(c);
            } else {
                pending.append(c);
            }
        }

        if (inQuotes) {
            String frag = pending.toString();
            if (!frag.isEmpty())
                result.add(java.util.Collections.singletonList(frag));
        } else {
            flushUnquotedToSections(pending, result);
        }
        return result;
    }

    private static void flushUnquotedToSections(StringBuilder buf, List<List<String>> result) {
        String text = buf.toString();
        if (text.isEmpty())
            return;
        String[] dotParts = text.split("\\.", -1);
        for (String dotPart : dotParts) {
            String trimmed = dotPart.trim();
            if (trimmed.isEmpty())
                continue;
            String[] spaceParts = trimmed.split("\\s+", -1);
            List<String> frags = new ArrayList<>();
            for (String sp : spaceParts) {
                String f = sp.trim();
                if (!f.isEmpty())
                    frags.add(f);
            }
            if (!frags.isEmpty())
                result.add(frags);
        }
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
            for (String frag : sections.get(i)) {
                if (!elemSection.contains(frag)) {
                    return false;
                }
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
        for (String frag : fragments)
        {
            if (lowerText.contains(frag) && !lowerOther.contains(frag))
                result.add(frag);
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
        if (isEmpty || partText == null || partText.isEmpty()) {
            return 0;
        }

        String lowerText = partText.toLowerCase();

        // --- ГРУППА 1: ПОЛНОЕ СОВПАДЕНИЕ ВСЕГО ФИЛЬТРА ЦЕЛИКОМ ВНУТРИ ЧАСТИ ---
        if (lowerText.contains(fullPattern)) {
            int fullIdx = lowerText.indexOf(fullPattern);
            if (fullIdx == 0) {
                return 4; // полное совпадение в начале первого слова
            }
            if (isWordBoundary(partText, fullIdx)) {
                return 3; // полное совпадение в начале любого слова
            }
            
            boolean crossesWords = false;
            for (int i = fullIdx; i < fullIdx + fullPattern.length(); i++) {
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
            if (isWordBoundary(partText, i)) {
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

            // БазоваяПремия: определяем по минимальному индексу совпавшего слова
            int wordsDiff = maxWordIdx - minWordIdx + 1;
            boolean noGaps = (wordsDiff == fragments.length);
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
        List<String> all = new ArrayList<>();
        for (List<String> sec : sections) {
            all.addAll(sec);
        }
        return all;
    }

    public List<HighlightRange> getHighlightRanges(String text) {
        List<HighlightRange> ranges = new ArrayList<>();
        if (isEmpty || text == null) return ranges;

        String lowerText = text.toLowerCase();
        for (String frag : getAllSectionFragments()) {
            int idx = lowerText.indexOf(frag);
            while (idx >= 0) {
                ranges.add(new HighlightRange(idx, frag.length()));
                idx = lowerText.indexOf(frag, idx + frag.length());
            }
        }
        return ranges;
    }

    public List<HighlightRange> getLastSectionHighlightRanges(String text) {
        List<HighlightRange> ranges = new ArrayList<>();
        if (isEmpty || text == null || sections.isEmpty()) return ranges;

        List<String> lastSection = sections.get(sections.size() - 1);
        String lowerText = text.toLowerCase();
        for (String frag : lastSection) {
            int idx = lowerText.indexOf(frag);
            while (idx >= 0) {
                ranges.add(new HighlightRange(idx, frag.length()));
                idx = lowerText.indexOf(frag, idx + frag.length());
            }
        }
        return ranges;
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