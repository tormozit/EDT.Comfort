package tormozit;

import java.util.Arrays;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Image;

/**
 * Как {@link org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider}, но с флагом
 * {@link StyledCellLabelProvider#COLORS_ON_SELECTION}. {@code DelegatingStyledCellLabelProvider}
 * не даёт способа передать этот флаг в конструктор, а без него JFace намеренно игнорирует цвета
 * {@code StyleRange} для ВЫДЕЛЕННЫХ строк (полагается на нативную подсветку выбора) — из-за этого
 * подсветка вхождения результата поиска (панели "Результаты поиска") пропадала именно на активной
 * строке, независимо от фона выделения.
 */
final class SelectionAwareStyledCellLabelProvider extends StyledCellLabelProvider
{
    private final IStyledLabelProvider styledLabelProvider;

    SelectionAwareStyledCellLabelProvider(IStyledLabelProvider styledLabelProvider)
    {
        super(COLORS_ON_SELECTION);
        this.styledLabelProvider = styledLabelProvider;
    }

    @Override
    public void update(ViewerCell cell)
    {
        Object element = cell.getElement();
        StyledString styledString = styledLabelProvider.getStyledText(element);
        String newText = styledString.toString();

        StyleRange[] oldStyleRanges = cell.getStyleRanges();
        StyleRange[] newStyleRanges = isOwnerDrawEnabled() ? styledString.getStyleRanges() : null;
        if (!Arrays.equals(oldStyleRanges, newStyleRanges))
        {
            cell.setStyleRanges(newStyleRanges);
            if (cell.getText().equals(newText))
                cell.setText(""); //$NON-NLS-1$
        }
        cell.setText(newText);
        cell.setImage(styledLabelProvider.getImage(element));
        super.update(cell);
    }

    /** Плоский текст ячейки для копирования ({@link FormTableInteraction}). */
    String textForCopy(Object element)
    {
        if (element == null)
            return ""; //$NON-NLS-1$
        StyledString styled = styledLabelProvider.getStyledText(element);
        if (styled == null)
            return ""; //$NON-NLS-1$
        String s = styled.getString();
        return s != null ? s : ""; //$NON-NLS-1$
    }

    @Override
    public void dispose()
    {
        styledLabelProvider.dispose();
        super.dispose();
    }

    /**
     * Извлекает {@link IStyledLabelProvider} для повторного оборачивания провайдера диалога
     * несколькими независимыми хуками, патчащими один и тот же {@code TableViewer} в произвольном
     * порядке (см. {@code FormMainAttributeTypeDecorator}, {@code ListItemSelectionDialogFilterHook}):
     * если текущий провайдер — уже наш {@link SelectionAwareStyledCellLabelProvider}, отдаёт то, что
     * внутри (стиль предыдущего хука не теряется); если штатный {@link IStyledLabelProvider} или
     * просто {@link ILabelProvider} — оборачивает как есть, без потери текста/изображения.
     */
    static IStyledLabelProvider unwrapOrAdapt(IBaseLabelProvider provider)
    {
        if (provider instanceof SelectionAwareStyledCellLabelProvider sacp)
            return sacp.styledLabelProvider;
        if (provider instanceof IStyledLabelProvider styled)
            return styled;
        if (provider instanceof ILabelProvider plain)
            return new PlainStyledAdapter(plain);
        return null;
    }

    private static final class PlainStyledAdapter extends LabelProvider implements IStyledLabelProvider
    {
        private final ILabelProvider base;

        PlainStyledAdapter(ILabelProvider base)
        {
            this.base = base;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            String text = base.getText(element);
            return new StyledString(text != null ? text : ""); //$NON-NLS-1$
        }

        @Override
        public Image getImage(Object element)
        {
            return base.getImage(element);
        }

        @Override
        public void dispose()
        {
            base.dispose();
            super.dispose();
        }
    }
}
