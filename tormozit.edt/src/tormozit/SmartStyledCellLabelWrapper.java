package tormozit;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.graphics.Image;

/**
 * Сохраняет штатный {@link StyledCellLabelProvider} EDT (чекбоксы, иконки, цвета)
 * и добавляет подсветку совпадений фильтра поверх {@link ViewerCell#getStyleRanges()}.
 * Реализует {@link ILabelProvider} — требуется навигатором Eclipse ({@code TreeFrame}).
 */
public final class SmartStyledCellLabelWrapper extends StyledCellLabelProvider
        implements SmartLabelHighlight, ILabelProvider
{
    private final StyledCellLabelProvider base;
    private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

    public SmartStyledCellLabelWrapper(StyledCellLabelProvider base)
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
        base.update(cell);
        if (cell == null || highlightMatcher.isEmpty)
            return;
        String text = cell.getText();
        if (text == null || text.isEmpty())
            return;
        SmartMatchHighlight.appendMatchRanges(cell, highlightMatcher.getHighlightRanges(text));
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
