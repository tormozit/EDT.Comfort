package tormozit;

import java.util.List;

import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.graphics.Image;

/**
 * Подсветка для {@link CellLabelProvider}/{@link org.eclipse.jface.viewers.ColumnLabelProvider}
 * (панель «Информационные базы», «Индексирование Git» — любой {@code ColumnViewer} без styled
 * label provider).
 */
final class CellLabelHighlightWrapper extends StyledCellLabelProvider
    implements SmartLabelHighlight, ILabelProvider
{
    private final CellLabelProvider base;
    private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

    CellLabelHighlightWrapper(CellLabelProvider base)
    {
        this.base = base;
    }

    @Override
    public void setHighlightPattern(String pattern)
    {
        highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
    }

    @Override
    public void update(ViewerCell cell)
    {
        if (base != null)
            base.update(cell);
        if (cell == null)
            return;
        // Вызываем всегда, а не только при непустом фильтре — иначе при очистке поля старые
        // StyleRange (SWT переиспользует TreeItem/TableItem между refresh-ами) остаются висеть.
        // appendMatchRanges сам корректно очищает ячейку при пустом списке диапазонов.
        String text = cell.getText();
        List<SmartMatcher.HighlightRange> ranges = !highlightMatcher.isEmpty && text != null && !text.isEmpty()
            ? highlightMatcher.getHighlightRanges(text) : List.of();
        SmartMatchHighlight.appendMatchRanges(cell, ranges);
    }

    @Override
    public String getText(Object element)
    {
        if (base instanceof ILabelProvider)
            return ((ILabelProvider) base).getText(element);
        Object text = Global.invoke(base, "getText", element); //$NON-NLS-1$
        return text instanceof String ? (String) text : ""; //$NON-NLS-1$
    }

    @Override
    public Image getImage(Object element)
    {
        if (base instanceof ILabelProvider)
            return ((ILabelProvider) base).getImage(element);
        Object img = Global.invoke(base, "getImage", element); //$NON-NLS-1$
        return img instanceof Image ? (Image) img : null;
    }

    @Override
    public String getToolTipText(Object element)
    {
        Object tip = Global.invoke(base, "getToolTipText", element); //$NON-NLS-1$
        return tip instanceof String ? (String) tip : null;
    }

    @Override
    public void addListener(ILabelProviderListener listener)
    {
        base.addListener(listener);
    }

    @Override
    public void removeListener(ILabelProviderListener listener)
    {
        base.removeListener(listener);
    }

    @Override
    public boolean isLabelProperty(Object element, String property)
    {
        return base.isLabelProperty(element, property);
    }

    @Override
    public void dispose()
    {
        base.dispose();
    }
}
