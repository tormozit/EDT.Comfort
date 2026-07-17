package tormozit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * Посимвольное выравнивание и раскраска различий двух строк.
 *
 * <p>Порт проверенного алгоритма (LCS-подстроки длиной от 3 символов + посимвольный
 * Левенштейн для несовпавших фрагментов), присланного пользователем как готовое HTML/JS-решение
 * для панели «Сравнение строк».
 */
public final class CompareCurrentLineDiff
{
    private CompareCurrentLineDiff()
    {
    }

    public enum CharType
    {
        /** Символ совпадает в обеих строках. */
        COMMON,
        /** Символ есть только в левой строке. */
        DELETE,
        /** Символ есть только в правой строке. */
        INSERT,
        /** Заполнитель-пробел на месте символа с другой стороны. */
        SPACE
    }

    /** Результат выравнивания: строки одинаковой длины с типом каждого символа. */
    public static final class AlignedResult
    {
        public final String left;
        public final String right;
        public final CharType[] leftTypes;
        public final CharType[] rightTypes;

        AlignedResult(String left, String right, CharType[] leftTypes, CharType[] rightTypes)
        {
            this.left = left;
            this.right = right;
            this.leftTypes = leftTypes;
            this.rightTypes = rightTypes;
        }
    }

    /** Выравнивает две строки для отображения с раскраской различий. */
    public static AlignedResult align(String text1, String text2)
    {
        String s1 = text1 != null ? text1 : ""; //$NON-NLS-1$
        String s2 = text2 != null ? text2 : ""; //$NON-NLS-1$

        if (s1.equals(s2))
            return new AlignedResult(s1, s2, commonTypes(s1.length()), commonTypes(s2.length()));

        List<Op> diff = computeHybridDiff(s1, s2);

        StringBuilder str1 = new StringBuilder();
        StringBuilder str2 = new StringBuilder();
        List<CharType> types1 = new ArrayList<>();
        List<CharType> types2 = new ArrayList<>();

        for (Op op : diff)
        {
            switch (op.type)
            {
            case COMMON:
                for (int i = 0; i < op.text.length(); i++)
                {
                    char ch = op.text.charAt(i);
                    str1.append(ch);
                    str2.append(ch);
                    types1.add(CharType.COMMON);
                    types2.add(CharType.COMMON);
                }
                break;
            case DELETE:
                for (int i = 0; i < op.text.length(); i++)
                {
                    str1.append(op.text.charAt(i));
                    str2.append(' ');
                    types1.add(CharType.DELETE);
                    types2.add(CharType.SPACE);
                }
                break;
            case INSERT:
                for (int i = 0; i < op.text.length(); i++)
                {
                    str1.append(' ');
                    str2.append(op.text.charAt(i));
                    types1.add(CharType.SPACE);
                    types2.add(CharType.INSERT);
                }
                break;
            case REPLACE:
                for (CharOp subOp : computeCharDiff(op.text1, op.text2))
                {
                    switch (subOp.type)
                    {
                    case COMMON:
                        str1.append(subOp.ch);
                        str2.append(subOp.ch);
                        types1.add(CharType.COMMON);
                        types2.add(CharType.COMMON);
                        break;
                    case DELETE:
                        str1.append(subOp.ch);
                        str2.append(' ');
                        types1.add(CharType.DELETE);
                        types2.add(CharType.SPACE);
                        break;
                    case INSERT:
                        str1.append(' ');
                        str2.append(subOp.ch);
                        types1.add(CharType.SPACE);
                        types2.add(CharType.INSERT);
                        break;
                    case REPLACE:
                        str1.append(subOp.ch1);
                        str2.append(subOp.ch2);
                        types1.add(CharType.DELETE);
                        types2.add(CharType.INSERT);
                        break;
                    }
                }
                break;
            }
        }

        return new AlignedResult(str1.toString(), str2.toString(),
            types1.toArray(new CharType[0]), types2.toArray(new CharType[0]));
    }

    private static CharType[] commonTypes(int length)
    {
        CharType[] types = new CharType[length];
        java.util.Arrays.fill(types, CharType.COMMON);
        return types;
    }

    private enum OpType
    {
        COMMON, DELETE, INSERT, REPLACE
    }

    private static final class Op
    {
        final OpType type;
        final String text;
        final String text1;
        final String text2;

        private Op(OpType type, String text, String text1, String text2)
        {
            this.type = type;
            this.text = text;
            this.text1 = text1;
            this.text2 = text2;
        }

        static Op common(String text)
        {
            return new Op(OpType.COMMON, text, null, null);
        }

        static Op delete(String text)
        {
            return new Op(OpType.DELETE, text, null, null);
        }

        static Op insert(String text)
        {
            return new Op(OpType.INSERT, text, null, null);
        }

        static Op replace(String text1, String text2)
        {
            return new Op(OpType.REPLACE, null, text1, text2);
        }
    }

    private static final class CharOp
    {
        final OpType type;
        final char ch;
        final char ch1;
        final char ch2;

        private CharOp(OpType type, char ch, char ch1, char ch2)
        {
            this.type = type;
            this.ch = ch;
            this.ch1 = ch1;
            this.ch2 = ch2;
        }

        static CharOp common(char ch)
        {
            return new CharOp(OpType.COMMON, ch, '\0', '\0');
        }

        static CharOp delete(char ch)
        {
            return new CharOp(OpType.DELETE, ch, '\0', '\0');
        }

        static CharOp insert(char ch)
        {
            return new CharOp(OpType.INSERT, ch, '\0', '\0');
        }

        static CharOp replace(char ch1, char ch2)
        {
            return new CharOp(OpType.REPLACE, '\0', ch1, ch2);
        }
    }

    private static final class Substring
    {
        final String text;
        final int start1;
        final int start2;

        Substring(String text, int start1, int start2)
        {
            this.text = text;
            this.start1 = start1;
            this.start2 = start2;
        }
    }

    private static List<Op> computeHybridDiff(String s1, String s2)
    {
        if (s1.equals(s2))
            return List.of(Op.common(s1));

        List<Substring> commonSubstrings = findLongestCommonSubstrings(s1, s2);
        if (commonSubstrings.isEmpty())
            return List.of(Op.replace(s1, s2));

        List<Op> result = new ArrayList<>();
        int pos1 = 0;
        int pos2 = 0;

        for (Substring cs : commonSubstrings)
        {
            if (cs.start1 > pos1 || cs.start2 > pos2)
            {
                String before1 = s1.substring(pos1, cs.start1);
                String before2 = s2.substring(pos2, cs.start2);

                if (!before1.isEmpty() && !before2.isEmpty())
                    result.add(Op.replace(before1, before2));
                else if (!before1.isEmpty())
                    result.add(Op.delete(before1));
                else if (!before2.isEmpty())
                    result.add(Op.insert(before2));
            }
            result.add(Op.common(cs.text));
            pos1 = cs.start1 + cs.text.length();
            pos2 = cs.start2 + cs.text.length();
        }

        if (pos1 < s1.length() || pos2 < s2.length())
        {
            String after1 = s1.substring(pos1);
            String after2 = s2.substring(pos2);
            if (!after1.isEmpty() && !after2.isEmpty())
                result.add(Op.replace(after1, after2));
            else if (!after1.isEmpty())
                result.add(Op.delete(after1));
            else if (!after2.isEmpty())
                result.add(Op.insert(after2));
        }

        return result;
    }

    /** Наибольшие общие подстроки (от 3 символов, без пересечений), отсортированные по позиции в {@code s1}. */
    private static List<Substring> findLongestCommonSubstrings(String s1, String s2)
    {
        int m = s1.length();
        int n = s2.length();
        if (m == 0 || n == 0)
            return List.of();

        int[][] dp = new int[m + 1][n + 1];
        int maxLength = 0;
        List<int[]> endings = new ArrayList<>();

        for (int i = 1; i <= m; i++)
        {
            for (int j = 1; j <= n; j++)
            {
                if (s1.charAt(i - 1) == s2.charAt(j - 1))
                {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > maxLength)
                    {
                        maxLength = dp[i][j];
                        endings.clear();
                        endings.add(new int[] { i, j });
                    }
                    else if (dp[i][j] == maxLength && maxLength > 0)
                        endings.add(new int[] { i, j });
                }
            }
        }

        if (maxLength < 3)
        {
            maxLength = 0;
            endings.clear();
            for (int i = 1; i <= m; i++)
            {
                for (int j = 1; j <= n; j++)
                {
                    if (s1.charAt(i - 1) == s2.charAt(j - 1))
                    {
                        dp[i][j] = dp[i - 1][j - 1] + 1;
                        if (dp[i][j] >= 3)
                        {
                            if (dp[i][j] > maxLength)
                            {
                                maxLength = dp[i][j];
                                endings.clear();
                                endings.add(new int[] { i, j });
                            }
                            else if (dp[i][j] == maxLength)
                                endings.add(new int[] { i, j });
                        }
                    }
                    else
                        dp[i][j] = 0;
                }
            }
        }

        if (maxLength == 0)
            return List.of();

        List<Substring> substrings = new ArrayList<>();
        for (int[] end : endings)
        {
            int i = end[0];
            int j = end[1];
            int start1 = i - maxLength;
            int start2 = j - maxLength;
            String text = s1.substring(start1, i);

            boolean overlapping = false;
            for (Substring sub : substrings)
            {
                if (!(start1 >= sub.start1 + sub.text.length()
                    || start1 + text.length() <= sub.start1
                    || start2 >= sub.start2 + sub.text.length()
                    || start2 + text.length() <= sub.start2))
                {
                    overlapping = true;
                    break;
                }
            }

            if (!overlapping)
                substrings.add(new Substring(text, start1, start2));
        }

        substrings.sort(Comparator.comparingInt(s -> s.start1));
        return substrings;
    }

    /** Посимвольный diff по Левенштейну с восстановлением пути (для несовпавших фрагментов). */
    private static List<CharOp> computeCharDiff(String s1, String s2)
    {
        int m = s1.length();
        int n = s2.length();
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 0; i <= m; i++)
        {
            for (int j = 0; j <= n; j++)
            {
                if (i == 0)
                    dp[i][j] = j;
                else if (j == 0)
                    dp[i][j] = i;
                else if (s1.charAt(i - 1) == s2.charAt(j - 1))
                    dp[i][j] = dp[i - 1][j - 1];
                else
                    dp[i][j] = 1 + Math.min(dp[i][j - 1], Math.min(dp[i - 1][j], dp[i - 1][j - 1]));
            }
        }

        LinkedList<CharOp> diff = new LinkedList<>();
        int i = m;
        int j = n;
        while (i > 0 || j > 0)
        {
            if (i > 0 && j > 0 && s1.charAt(i - 1) == s2.charAt(j - 1))
            {
                diff.addFirst(CharOp.common(s1.charAt(i - 1)));
                i--;
                j--;
            }
            else if (j > 0 && (i == 0 || (dp[i][j - 1] <= dp[i - 1][j] && dp[i][j - 1] <= dp[i - 1][j - 1])))
            {
                diff.addFirst(CharOp.insert(s2.charAt(j - 1)));
                j--;
            }
            else if (i > 0 && (j == 0 || (dp[i - 1][j] <= dp[i][j - 1] && dp[i - 1][j] <= dp[i - 1][j - 1])))
            {
                diff.addFirst(CharOp.delete(s1.charAt(i - 1)));
                i--;
            }
            else
            {
                diff.addFirst(CharOp.replace(s1.charAt(i - 1), s2.charAt(j - 1)));
                i--;
                j--;
            }
        }
        return diff;
    }
}
