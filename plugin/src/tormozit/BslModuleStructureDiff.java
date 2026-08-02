package tormozit;

import java.util.ArrayList;
import java.util.List;

import tormozit.BslModuleStructureParser.ParseOutcome;
import tormozit.BslModuleStructureParser.SectionNode;

/**
 * Сопоставление структуры модуля слева/справа для панели «Структура»
 * (см. {@link CompareDialogCurrentLinesHook}) и фильтрация до узлов, реально содержащих
 * различия (изменённые/добавленные/удалённые секции) — полный список секций не нужен.
 *
 * <p>Сопоставление узлов одного уровня — по равенству метки (имя+тип секции) через
 * наибольшую общую подпоследовательность (LCS), без обращения к сессии сравнения EDT
 * (см. {@link BslModuleStructureParser} — почему полноценный align недоступен без неё).
 * Тот же класс приближённого сопоставления по духу, что и {@link CompareLineRangeMatcher}
 * для отдельных строк.
 */
final class BslModuleStructureDiff
{
    private BslModuleStructureDiff()
    {
    }

    enum Kind
    {
        ROOT, CHANGED, ADDED, REMOVED, SYNTAX_ERROR
    }

    static final class DiffNode
    {
        final String label;
        final Kind kind;
        final int leftOffset, leftLength; // -1 при отсутствии слева
        final int rightOffset, rightLength; // -1 при отсутствии справа
        final List<DiffNode> children = new ArrayList<>();
        /** {@code null} у корня — нужен {@code ITreeContentProvider.getParent} (см. CompareDialogStructurePanel). */
        DiffNode parent;

        DiffNode(String label, Kind kind, int leftOffset, int leftLength, int rightOffset, int rightLength)
        {
            this.label = label;
            this.kind = kind;
            this.leftOffset = leftOffset;
            this.leftLength = leftLength;
            this.rightOffset = rightOffset;
            this.rightLength = rightLength;
        }

        void addChild(DiffNode child)
        {
            child.parent = this;
            children.add(child);
        }

        boolean hasLeft()
        {
            return leftOffset >= 0;
        }

        boolean hasRight()
        {
            return rightOffset >= 0;
        }
    }

    static final class Result
    {
        final DiffNode root; // null, если хотя бы одна сторона не разобралась
        final String leftError;
        final String rightError;

        private Result(DiffNode root, String leftError, String rightError)
        {
            this.root = root;
            this.leftError = leftError;
            this.rightError = rightError;
        }
    }

    static Result diff(String leftText, String rightText, String leftLabel, String rightLabel)
    {
        ParseOutcome left = BslModuleStructureParser.parse(leftText);
        ParseOutcome right = BslModuleStructureParser.parse(rightText);
        if (left.root == null || right.root == null)
            return new Result(null, left.fatalError, right.fatalError);

        DiffNode root = new DiffNode("Модуль", Kind.ROOT, 0, leftText.length(), 0, rightText.length()); //$NON-NLS-1$
        /*
         * Синтаксическая ошибка — отдельный узел (не часть сопоставления секций): показываем
         * ту часть структуры, что удалось разобрать (см. BslModuleStructureParser), плюс явный
         * узел с местом ошибки — выбор этого узла подсвечивает место ошибки в тексте (см.
         * StructureToggleController.onNodeSelected, переиспользует ту же логику, что и обычные
         * узлы, через left/rightOffset). Явно называем последствие (неполный разбор), а не
         * просто дублируем текст ошибки парсера — иначе не очевидно, зачем этот узел вообще
         * показан.
         */
        if (left.syntaxErrorMessage != null)
            root.addChild(new DiffNode(
                "Неполная структура " + labelOrDefault(leftLabel, "слева") + ": " + left.syntaxErrorMessage, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Kind.SYNTAX_ERROR, Math.max(0, left.syntaxErrorOffset), 1, -1, -1));
        if (right.syntaxErrorMessage != null)
            root.addChild(new DiffNode(
                "Неполная структура " + labelOrDefault(rightLabel, "справа") + ": " + right.syntaxErrorMessage, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Kind.SYNTAX_ERROR, -1, -1, Math.max(0, right.syntaxErrorOffset), 1));
        matchChildren(root, left.root.children, right.root.children, leftText, rightText);
        return new Result(root, null, null);
    }

    private static String labelOrDefault(String text, String fallback)
    {
        return text != null && !text.isBlank() ? text : fallback;
    }

    private static void matchChildren(DiffNode parent, List<SectionNode> leftChildren,
        List<SectionNode> rightChildren, String leftText, String rightText)
    {
        for (int[] pair : lcsAlign(leftChildren, rightChildren))
        {
            int li = pair[0];
            int ri = pair[1];
            if (li >= 0 && ri >= 0)
            {
                SectionNode l = leftChildren.get(li);
                SectionNode r = rightChildren.get(ri);
                DiffNode node = new DiffNode(l.label, Kind.CHANGED, l.offset, l.length, r.offset, r.length);
                matchChildren(node, l.children, r.children, leftText, rightText);
                boolean textDiffers = !safeSubstring(leftText, l.offset, l.length)
                    .equals(safeSubstring(rightText, r.offset, r.length));
                if (textDiffers || !node.children.isEmpty())
                    parent.addChild(node);
            }
            else if (li >= 0)
            {
                SectionNode l = leftChildren.get(li);
                parent.addChild(new DiffNode(l.label, Kind.REMOVED, l.offset, l.length, -1, -1));
            }
            else
            {
                SectionNode r = rightChildren.get(ri);
                parent.addChild(new DiffNode(r.label, Kind.ADDED, -1, -1, r.offset, r.length));
            }
        }
    }

    private static String safeSubstring(String text, int offset, int length)
    {
        if (text == null || offset < 0 || length < 0 || offset + length > text.length())
            return ""; //$NON-NLS-1$
        return text.substring(offset, offset + length);
    }

    /**
     * LCS по равенству {@code label} — список пар индексов {@code {leftIndex, rightIndex}}
     * в порядке возрастания, {@code -1} на месте индекса означает отсутствие пары
     * (добавленный/удалённый узел).
     */
    private static List<int[]> lcsAlign(List<SectionNode> left, List<SectionNode> right)
    {
        int n = left.size();
        int m = right.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                dp[i][j] = left.get(i).label.equals(right.get(j).label) ? dp[i + 1][j + 1] + 1
                    : Math.max(dp[i + 1][j], dp[i][j + 1]);

        List<int[]> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m)
        {
            if (left.get(i).label.equals(right.get(j).label))
            {
                result.add(new int[] { i, j });
                i++;
                j++;
            }
            else if (dp[i + 1][j] >= dp[i][j + 1])
            {
                result.add(new int[] { i, -1 });
                i++;
            }
            else
            {
                result.add(new int[] { -1, j });
                j++;
            }
        }
        while (i < n)
        {
            result.add(new int[] { i, -1 });
            i++;
        }
        while (j < m)
        {
            result.add(new int[] { -1, j });
            j++;
        }
        return result;
    }
}
