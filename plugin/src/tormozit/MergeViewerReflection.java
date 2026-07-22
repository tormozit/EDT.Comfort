package tormozit;

import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Label;

/**
 * Извлечение {@link StyledText} панели merge-вьюера через приватное поле
 * типа {@code org.eclipse.compare.internal.MergeSourceViewer}
 * (недокументированный внутренний API Eclipse Compare, публичного способа нет).
 *
 * <p>Работает и для штатного {@code org.eclipse.compare.contentmergeviewer.TextMergeViewer}
 * (поля {@code fLeft}/{@code fRight}), и для собственного класса 1C
 * {@code com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeViewer}
 * (поля {@code leftViewer}/{@code rightViewer}/{@code resultViewer}) — оба
 * используют один и тот же внутренний {@code MergeSourceViewer} с публичным
 * методом {@code getSourceViewer(): SourceViewer}.
 */
public final class MergeViewerReflection
{
    private MergeViewerReflection()
    {
    }

    public static StyledText extractStyledText(Object mergeViewer, String fieldName)
    {
        Object mergeSourceViewer = Global.getField(mergeViewer, fieldName);
        if (mergeSourceViewer == null)
            return null;
        Object sourceViewer = Global.invoke(mergeSourceViewer, "getSourceViewer"); //$NON-NLS-1$
        if (sourceViewer instanceof SourceViewer sv)
            return sv.getTextWidget();
        return null;
    }

    /**
     * Текст заголовка панели (например, {@code ThreeSideTextMergeViewer.leftLabel}/
     * {@code resultLabel}/{@code rightLabel} — те же заголовки, что показаны над
     * панелями сравнения) — тот же приём, приватное поле типа {@link CLabel}/{@link Label}.
     */
    public static String extractLabelText(Object owner, String fieldName)
    {
        Object field = Global.getField(owner, fieldName);
        if (field instanceof CLabel label && !label.isDisposed())
            return label.getText();
        if (field instanceof Label label && !label.isDisposed())
            return label.getText();
        return null;
    }

    /**
     * Запись текста в заголовок панели ({@link CLabel}/{@link Label}), например
     * {@code ContentMergeViewer.fLeftLabel}/{@code fRightLabel}.
     *
     * @return {@code true}, если виджет найден и текст записан
     */
    public static boolean setLabelText(Object owner, String fieldName, String text)
    {
        if (text == null)
            return false;
        Object field = Global.getField(owner, fieldName);
        if (field instanceof CLabel label && !label.isDisposed())
        {
            label.setText(text);
            return true;
        }
        if (field instanceof Label label && !label.isDisposed())
        {
            label.setText(text);
            return true;
        }
        return false;
    }
}
