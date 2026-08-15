package tormozit;

import java.util.ArrayList;
import java.util.List;

public class SmartCodeMatcher extends SmartMatcher {

    /** Служебные символы начала слова: аннотация, препроцессор, метка. */
    private static final String SERVICE_PREFIX_CHARS = "&#~";

    /** Первый символ фильтра, если он служебный; иначе 0. */
    private final char servicePrefix;
    /** Тот же фильтр без служебного символа — для имён, которые его не содержат. */
    private SmartCodeMatcher bareMatcher;

    public SmartCodeMatcher(String filterPattern) {
        super(filterPattern);
        servicePrefix = !isEmpty && SERVICE_PREFIX_CHARS.indexOf(fullPattern.charAt(0)) >= 0
            ? fullPattern.charAt(0) : 0;
    }

    /**
     * Фильтр начинается со служебного символа, а имя предложения — нет: сравниваем без него.
     * EDT отдаёт аннотации именем без амперсанда (иконка отдельно), а слова ИР — с ним;
     * без этого при вводе "&На" отбор по имени "НаКлиенте" давал 0 и список пустел.
     */
    private SmartCodeMatcher matcherForName(String name) {
        if (servicePrefix == 0 || name == null || name.isEmpty()
            || name.charAt(0) == servicePrefix) {
            return this;
        }
        if (bareMatcher == null)
            bareMatcher = new SmartCodeMatcher(fullPattern.substring(1));
        return bareMatcher;
    }

    @Override
    public int computeNamePremium(String text) {
        String name = extractName(text);
        SmartCodeMatcher matcher = matcherForName(name);
        if (matcher != this)
            return matcher.isEmpty ? 1 : matcher.computeAdaptivePremium(name);
        return computeAdaptivePremium(name);
    }

    /** Фильтр — один служебный символ ({@code &}, {@code #}, {@code ~}) без букв. */
    public boolean isServicePrefixOnly() {
        return servicePrefix != 0 && fullPattern.length() == 1;
    }

    /**
     * Премия без «прощения» ведущего служебного символа — для источников, которые его
     * всегда пишут в имени (слова ИР). Иначе фильтр из одного символа пропускал бы
     * все слова ИР подряд: при "&" в списке появлялись "Если … Тогда", "Новый()" и т.п.
     */
    public int computeNamePremiumStrict(String text) {
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
        return scoreAdaptiveMatch(match);
    }

    private static int scoreAdaptiveMatch(AdaptiveWordMatch match) {
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

    /** Состояние одной ветки параллельного сопоставления. */
    private static final class MatchState {
        int wordIdx;
        int offsetInWord;
        int firstWord = -1;
        int lastWord = -1;
        int matchedWords;
        final List<HighlightRange> ranges = new ArrayList<>();

        MatchState copy() {
            MatchState c = new MatchState();
            c.wordIdx = wordIdx;
            c.offsetInWord = offsetInWord;
            c.firstWord = firstWord;
            c.lastWord = lastWord;
            c.matchedWords = matchedWords;
            c.ranges.addAll(ranges);
            return c;
        }

        AdaptiveWordMatch toResult() {
            AdaptiveWordMatch m = new AdaptiveWordMatch();
            m.matched = true;
            m.firstWord = firstWord;
            m.lastWord = lastWord;
            m.matchedWords = matchedWords;
            m.ranges.addAll(ranges);
            return m;
        }
    }

    /**
     * Параллельные ветки: на каждый символ фильтра — продолжение в текущем слове
     * или начало одного из следующих слов; из успешных веток выбирается лучшая.
     */
    private AdaptiveWordMatch matchAdaptiveWords(String partText) {
        AdaptiveWordMatch empty = new AdaptiveWordMatch();
        List<String> words = splitWords(partText);
        if (words.isEmpty())
            return empty;

        int[] wordStarts = buildWordStarts(words);
        List<MatchState> active = new ArrayList<>();
        MatchState initial = new MatchState();
        initial.wordIdx = 0;
        initial.offsetInWord = 0;
        active.add(initial);

        for (int fi = 0; fi < fullPattern.length(); fi++) {
            char fc = fullPattern.charAt(fi);
            List<MatchState> next = new ArrayList<>();
            for (MatchState state : active)
                expandMatchState(state, fc, words, wordStarts, next);
            if (next.isEmpty())
                return empty;
            active = next;
        }

        AdaptiveWordMatch best = empty;
        int bestScore = -1;
        for (MatchState state : active) {
            AdaptiveWordMatch candidate = state.toResult();
            int score = scoreAdaptiveMatch(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private void expandMatchState(MatchState state, char fc,
                                  List<String> words, int[] wordStarts,
                                  List<MatchState> next) {
        int w = state.wordIdx;
        int off = state.offsetInWord;

        // Продолжение в текущем слове
        if (w < words.size() && off < words.get(w).length()
            && charEquals(words.get(w).charAt(off), fc)) {
            MatchState cont = state.copy();
            recordChar(cont, w, off, wordStarts);
            cont.wordIdx = w;
            cont.offsetInWord = off + 1;
            next.add(cont);
        }

        // Начало одного из оставшихся слов
        int startWord = off == 0 ? w : w + 1;
        for (int w2 = startWord; w2 < words.size(); w2++) {
            if (w2 == w && off > 0)
                continue;
            String word = words.get(w2);
            if (word.isEmpty())
                continue;
            if (!charEquals(word.charAt(0), fc))
                continue;
            MatchState jump = state.copy();
            recordChar(jump, w2, 0, wordStarts);
            jump.wordIdx = w2;
            jump.offsetInWord = 1;
            next.add(jump);
        }
    }

    private void recordChar(MatchState state, int wordIdx, int offsetInWord, int[] wordStarts) {
        int absPos = wordStarts[wordIdx] + offsetInWord;
        if (!state.ranges.isEmpty()) {
            HighlightRange last = state.ranges.get(state.ranges.size() - 1);
            if (last.offset + last.length == absPos) {
                state.ranges.set(state.ranges.size() - 1,
                    new HighlightRange(last.offset, last.length + 1));
            } else {
                state.ranges.add(new HighlightRange(absPos, 1));
                onNewWord(state, wordIdx);
            }
        } else {
            state.ranges.add(new HighlightRange(absPos, 1));
            onNewWord(state, wordIdx);
        }
    }

    private static void onNewWord(MatchState state, int wordIdx) {
        if (state.firstWord < 0)
            state.firstWord = wordIdx;
        if (state.lastWord != wordIdx) {
            if (state.matchedWords == 0)
                state.matchedWords = 1;
            else
                state.matchedWords++;
            state.lastWord = wordIdx;
        } else if (state.matchedWords == 0) {
            state.matchedWords = 1;
            state.lastWord = wordIdx;
        }
    }

    private static boolean charEquals(char a, char b) {
        return Character.toLowerCase(a) == Character.toLowerCase(b);
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
        SmartCodeMatcher matcher = matcherForName(namePart);
        if (matcher != this)
            return matcher.isEmpty ? ranges : matcher.getHighlightRanges(text);
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
