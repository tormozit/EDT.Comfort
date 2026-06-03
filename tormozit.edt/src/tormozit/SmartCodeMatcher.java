package tormozit;

import java.util.ArrayList;
import java.util.List;

public class SmartCodeMatcher extends SmartMatcher {

    public SmartCodeMatcher(String filterPattern) {
        super(filterPattern);
    }

    @Override
    public int computeNamePremium(String text) {
        return computeAdaptivePremium(extractName(text));
    }

    @Override
    public int computeParamPremium(String text) {
        return computeAdaptivePremium(extractParams(text));
    }

    /** Извлекает имя метода/идентификатора, отсекая параметры и тип */
    private String extractName(String text) {
        if (text == null) return "";
        // Отрезаем параметры
        int parenIdx = text.indexOf('(');
        String withoutParams = (parenIdx >= 0) ? text.substring(0, parenIdx).trim() : text.trim();
        // Отрезаем тип после двоеточия
        int colonIdx = withoutParams.indexOf(':');
        return (colonIdx >= 0) ? withoutParams.substring(0, colonIdx).trim() : withoutParams;
    }

    private String extractParams(String text) {
        if (text == null) return "";
        int parenIdx = text.indexOf('(');
        return (parenIdx >= 0) ? text.substring(parenIdx).trim() : "";
    }

    private int computeAdaptivePremium(String partText) {
        if (isEmpty || partText == null || partText.isEmpty()) {
            return 0;
        }
        String lower = partText.toLowerCase();
        int idx = lower.indexOf(fullPattern);
        if (idx >= 0) {
            if (idx == 0) return 40;
            if (isWordBoundary(partText, idx)) return 30;
            boolean crosses = false;
            for (int i = idx + 1; i < idx + fullPattern.length(); i++) {
                if (isWordBoundary(partText, i)) { crosses = true; break; }
            }
            return crosses ? 5 : 15;
        }
        AdaptiveWordMatch match = matchAdaptiveWords(partText);
        if (!match.matched)
            return 0;
        int gap = match.lastWord - match.firstWord + 1;
        boolean consecutive = (gap == match.matchedWords);
        if (match.firstWord == 0) {
            return consecutive ? 35 : 20;
        }
        return consecutive ? 18 : 8;
    }

    /** Результат посимвольного сопоставления фильтра по словам CamelCase. */
    private static final class AdaptiveWordMatch {
        boolean matched;
        int firstWord = -1;
        int lastWord = -1;
        int matchedWords;
        final List<HighlightRange> ranges = new ArrayList<>();
    }

    /**
     * Один проход: фильтр по префиксам слов + хвост только как startsWith следующего слова.
     * Позиции подсветки — {@code wordStarts[i] + offset} внутри слова.
     */
    private AdaptiveWordMatch matchAdaptiveWords(String partText) {
        AdaptiveWordMatch result = new AdaptiveWordMatch();
        List<String> words = splitWords(partText);
        if (words.isEmpty())
            return result;

        int[] wordStarts = buildWordStarts(words);
        String remaining = fullPattern;
        int w = 0;
        while (!remaining.isEmpty() && w < words.size()) {
            String wordLower = words.get(w).toLowerCase();
            int common = commonPrefixLength(remaining, wordLower);
            if (common > 0) {
                if (result.firstWord == -1)
                    result.firstWord = w;
                result.lastWord = w;
                result.matchedWords++;
                result.ranges.add(new HighlightRange(wordStarts[w], common));
                remaining = remaining.substring(common);
                w++;
            } else {
                w++;
            }
        }
        if (!remaining.isEmpty()) {
            int start = (result.lastWord == -1) ? 0 : result.lastWord + 1;
            for (int i = start; i < words.size(); i++) {
                String wordLower = words.get(i).toLowerCase();
                if (wordLower.startsWith(remaining)) {
                    if (result.firstWord == -1)
                        result.firstWord = i;
                    result.lastWord = i;
                    result.matchedWords++;
                    result.ranges.add(new HighlightRange(wordStarts[i], remaining.length()));
                    remaining = "";
                    break;
                }
            }
        }
        result.matched = remaining.isEmpty();
        return result;
    }

    private static int[] buildWordStarts(List<String> words) {
        int[] starts = new int[words.size()];
        int pos = 0;
        for (int i = 0; i < words.size(); i++) {
            starts[i] = pos;
            pos += words.get(i).length();
        }
        return starts;
    }

    private static int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int n = 0;
        while (n < max && a.charAt(n) == b.charAt(n))
            n++;
        return n;
    }

    private List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        if (text == null || text.isEmpty()) return words;
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || isDelimiter(text.charAt(i)) || isWordBoundary(text, i)) {
                if (i > start) words.add(text.substring(start, i));
                start = i + (i < text.length() && isDelimiter(text.charAt(i)) ? 1 : 0);
            }
        }
        return words;
    }

    private boolean isDelimiter(char c) {
        return c == '_' || c == ' ' || c == '-' || c == '.';
    }

    @Override
    public List<HighlightRange> getHighlightRanges(String text) {
        List<HighlightRange> ranges = new ArrayList<>();
        if (isEmpty || text == null) return ranges;
        // Подсвечиваем только в имени, не в параметрах и не в типе
        String namePart = extractName(text);
        String lowerName = namePart.toLowerCase();
        String lowerFull = fullPattern.toLowerCase();

        // Сначала пробуем полное совпадение
        int fullIdx = lowerName.indexOf(lowerFull);
        if (fullIdx >= 0) {
            ranges.add(new HighlightRange(fullIdx, lowerFull.length()));
            return ranges;
        }

        AdaptiveWordMatch match = matchAdaptiveWords(namePart);
        return match.matched ? match.ranges : ranges;
    }
}