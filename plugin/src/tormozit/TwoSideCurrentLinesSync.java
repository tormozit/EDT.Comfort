package tormozit;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Синхронизация панели «Текущая строка» ({@link CompareCurrentLinesPanel}, две строки —
 * индексы {@code 0}=левая, {@code 1}=правая) с парой панелей двустороннего сравнения.
 *
 * <p>Общая логика для {@link PasteWithCompareActions} (свой диалог, панели —
 * {@code fLeft}/{@code fRight} штатного {@code TextMergeViewer}) и
 * {@link GitCompareCurrentLinesHook} (чужой редактор Git-сравнения, тот же
 * {@code TextMergeViewer}, но встроенный реактивно).
 *
 * <p>«Поменять местами» ({@link CompareConfiguration#MIRRORED}) запоминается в preference
 * и может быть уже включено при открытии. Семантические подписи (левый/правый input)
 * задаёт вызывающий; визуальный порядок (верх=слева, низ=справа) выставляется по
 * {@link CompareConfiguration#isMirrored()} сразу и отложенно — штатный swap/refresh
 * часто отрабатывает после нашего первого attach.
 */
public final class TwoSideCurrentLinesSync
{
    private static final String HOOK_MARKER_KEY = "tormozit.twoSideCurrentLinesHooked"; //$NON-NLS-1$
    private static final String MIRRORED_LISTENER_KEY = "tormozit.twoSideMirroredLabelListener"; //$NON-NLS-1$
    private static final int[] MIRROR_APPLY_DELAYS_MS = { 0, 50, 150, 400 };

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

    /**
     * Как {@link #hook(CompareCurrentLinesPanel, StyledText, StyledText)}, плюс подписи
     * панели/шапки с учётом {@code MIRRORED} (в т.ч. уже включённого при открытии).
     *
     * @param semanticLeft  подпись семантического левого input (до зеркала)
     * @param semanticRight подпись семантического правого input (до зеркала)
     */
    public static void hook(CompareCurrentLinesPanel panel, StyledText leftText, StyledText rightText,
        TextMergeViewer viewer, CompareConfiguration config, String semanticLeft, String semanticRight)
    {
        hook(panel, leftText, rightText);
        hookMirroredLabelRefresh(panel, leftText, rightText, viewer, config, semanticLeft, semanticRight);
    }

    /**
     * Визуальная подпись стороны: при {@code mirrored} семантические стороны меняются местами.
     */
    public static String visualSideLabel(String semanticLeft, String semanticRight, boolean mirrored,
        boolean left)
    {
        if (mirrored)
            return left ? semanticRight : semanticLeft;
        return left ? semanticLeft : semanticRight;
    }

    /**
     * Выставляет подписи панели и шапки вьюера по текущему {@code isMirrored()}
     * из семантических (незеркальных) подписей сторон.
     */
    public static void applyMirroredLabels(CompareCurrentLinesPanel panel, TextMergeViewer viewer,
        String semanticLeft, String semanticRight, boolean mirrored)
    {
        if (panel == null)
            return;
        Control panelControl = panel.getControl();
        if (panelControl == null || panelControl.isDisposed())
            return;

        String visualLeft = visualSideLabel(semanticLeft, semanticRight, mirrored, true);
        String visualRight = visualSideLabel(semanticLeft, semanticRight, mirrored, false);
        if (visualLeft != null)
            panel.setLabelText(0, visualLeft);
        if (visualRight != null)
            panel.setLabelText(1, visualRight);

        if (viewer != null)
        {
            MergeViewerReflection.setLabelText(viewer, "fLeftLabel", visualLeft); //$NON-NLS-1$
            MergeViewerReflection.setLabelText(viewer, "fRightLabel", visualRight); //$NON-NLS-1$
        }
    }

    private static void hookMirroredLabelRefresh(CompareCurrentLinesPanel panel, StyledText leftText,
        StyledText rightText, TextMergeViewer viewer, CompareConfiguration config,
        String semanticLeft, String semanticRight)
    {
        if (panel == null || viewer == null || config == null)
            return;
        Control panelControl = panel.getControl();
        if (panelControl == null || panelControl.isDisposed())
            return;

        Object previous = panelControl.getData(MIRRORED_LISTENER_KEY);
        if (previous instanceof Object[] prev && prev.length == 2
            && prev[0] instanceof CompareConfiguration oldConfig
            && prev[1] instanceof IPropertyChangeListener oldListener)
        {
            oldConfig.removePropertyChangeListener(oldListener);
        }

        final String semLeft = semanticLeft != null ? semanticLeft : ""; //$NON-NLS-1$
        final String semRight = semanticRight != null ? semanticRight : ""; //$NON-NLS-1$

        Runnable applyNow = () ->
        {
            if (panelControl.isDisposed())
                return;
            applyMirroredLabels(panel, viewer, semLeft, semRight, config.isMirrored());
            StyledText source = leftText != null && !leftText.isDisposed() ? leftText
                : rightText != null && !rightText.isDisposed() ? rightText : null;
            if (source != null)
                sync(source, panel, leftText, rightText);
        };

        /* Сразу — на случай, если MIRRORED уже из preference; плюс отложенно после refresh. */
        applyNow.run();
        scheduleMirrorApplies(panelControl, applyNow);

        IPropertyChangeListener listener = event ->
        {
            if (!CompareConfiguration.MIRRORED.equals(event.getProperty()))
                return;
            Display display = Display.getCurrent();
            if (display == null)
                display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(applyNow);
        };
        config.addPropertyChangeListener(listener);
        panelControl.setData(MIRRORED_LISTENER_KEY, new Object[] { config, listener });
        panelControl.addDisposeListener(e ->
        {
            config.removePropertyChangeListener(listener);
            if (!panelControl.isDisposed())
                panelControl.setData(MIRRORED_LISTENER_KEY, null);
        });
    }

    private static void scheduleMirrorApplies(Control panelControl, Runnable applyNow)
    {
        Display display = panelControl.getDisplay();
        if (display == null || display.isDisposed())
            return;
        for (int delayMs : MIRROR_APPLY_DELAYS_MS)
        {
            int delay = delayMs;
            Runnable tick = () ->
            {
                if (panelControl.isDisposed())
                    return;
                applyNow.run();
            };
            if (delay <= 0)
                display.asyncExec(tick);
            else
                display.timerExec(delay, tick);
        }
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
        }
    }
}
