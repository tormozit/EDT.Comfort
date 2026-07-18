package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;

/**
 * Синхронизация панели «Текущая строка» ({@link CompareCurrentLinesPanel}, две строки —
 * индексы {@code 0}=левая, {@code 1}=правая) с парой панелей двустороннего сравнения.
 *
 * <p>Общая логика для {@link PasteWithCompareActions} (свой диалог, панели —
 * {@code fLeft}/{@code fRight} штатного {@code TextMergeViewer}) и
 * {@link GitCompareCurrentLinesHook} (чужой редактор Git-сравнения, тот же
 * {@code TextMergeViewer}, но встроенный реактивно).
 */
public final class TwoSideCurrentLinesSync
{
    private static final String HOOK_MARKER_KEY = "tormozit.twoSideCurrentLinesHooked"; //$NON-NLS-1$

    private TwoSideCurrentLinesSync()
    {
    }

    /** Вешает слушатели каретки/правки на обе панели и сразу выполняет первую синхронизацию. */
    public static void hook(CompareCurrentLinesPanel panel, StyledText leftText, StyledText rightText)
    {
        hookStyledText(leftText, panel, leftText, rightText);
        hookStyledText(rightText, panel, leftText, rightText);

        StyledText initialSource = leftText != null ? leftText : rightText;
        if (initialSource != null)
            sync(initialSource, panel, leftText, rightText);
    }

    private static void hookStyledText(StyledText styledText, CompareCurrentLinesPanel panel,
        StyledText leftText, StyledText rightText)
    {
        if (styledText == null || styledText.isDisposed())
            return;
        if (Boolean.TRUE.equals(styledText.getData(HOOK_MARKER_KEY)))
            return;
        styledText.setData(HOOK_MARKER_KEY, Boolean.TRUE);

        styledText.addCaretListener(e -> sync(styledText, panel, leftText, rightText));
        styledText.addListener(SWT.Modify, e -> sync(styledText, panel, leftText, rightText));
    }

    /**
     * Обновляет обе панели «Текущая строка» одновременно: строка стороны, где сдвинулась
     * каретка ({@code source}), и сопоставленная ей строка другой стороны (через
     * {@link CompareLineRangeMatcher} по всему тексту); если строка не сопоставлена —
     * другая сторона показывает пустоту.
     */
    private static void sync(StyledText source, CompareCurrentLinesPanel panel,
        StyledText leftText, StyledText rightText)
    {
        if (leftText == null || leftText.isDisposed() || rightText == null || rightText.isDisposed())
            return;

        try
        {
            boolean sourceIsLeft = source == leftText;
            StyledText otherText = sourceIsLeft ? rightText : leftText;

            int sourceLine = CompareLineRangeMatcher.lineAtCaret(source);
            String sourceLineText = CompareLineRangeMatcher.lineOrEmpty(source, sourceLine);

            int matchedOtherLine = CompareLineRangeMatcher.findMatchedLine(source, sourceLine, otherText);
            String otherLineText = matchedOtherLine >= 0
                ? CompareLineRangeMatcher.lineOrEmpty(otherText, matchedOtherLine)
                : null;

            String leftLineText = sourceIsLeft ? sourceLineText : otherLineText;
            String rightLineText = sourceIsLeft ? otherLineText : sourceLineText;

            if (leftLineText == null || rightLineText == null)
            {
                panel.renderPlain(0, leftLineText);
                panel.renderPlain(1, rightLineText);
                panel.resetScroll();
                return;
            }

            CompareCurrentLineDiff.AlignedResult aligned = panel.renderPair(0, 1, leftLineText, rightLineText);
            panel.scrollToFirstDifference(panel.getRow(1), aligned.rightTypes);
        }
        catch (Exception e)
        {
            // Виджет мог измениться во время пересчёта (см. CompareLineRangeMatcher.findMatchedLine) — не крашим UI.
            Global.tempLogException("showInModule", "TwoSideCurrentLinesSync.sync", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
