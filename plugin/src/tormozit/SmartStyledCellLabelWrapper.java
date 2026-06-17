package tormozit;

import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.ViewerColumn;
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
    private SmartOutlineFilter bslFlatFilter;
    private IBaseLabelProvider bslFlatLabelSource;
    private BslOutlineEventsSupport.SubscriptionFlatLabels bslSubscriptionFlatLabels;

    public SmartStyledCellLabelWrapper(StyledCellLabelProvider base)
    {
        this.base = base;
    }

    /** Плоский Quick Outline BSL: подпись обработчика подписки (дерево или плоский список). */
    public void setBslFlatSubscriptionLabels(SmartOutlineFilter filter, IBaseLabelProvider labelSource,
            BslOutlineEventsSupport.SubscriptionFlatLabels subscriptionFlatLabels)
    {
        this.bslFlatFilter = filter;
        this.bslFlatLabelSource = labelSource;
        this.bslSubscriptionFlatLabels = subscriptionFlatLabels;
    }

    @Override
    public void initialize(ColumnViewer viewer, ViewerColumn column)
    {
        super.initialize(viewer, column);
        Global.invoke(base, "initialize", viewer, column); //$NON-NLS-1$
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
        if (cell != null)
        {
            String text = cell.getText();
            String flat = resolveSubscriptionLabelOverride(cell.getElement());
            if (flat != null)
                text = flat;
            if (text != null)
                cell.setText(text);
        }
        if (cell == null || highlightMatcher.isEmpty)
            return;
        String text = cell.getText();
        if (text == null || text.isEmpty())
            return;
        // НЕ КРАСИТЬ СТРОКУ, ЕСЛИ НЕТ ПОЛНОГО СОВПАДЕНИЯ ВСЕХ СЛОВ ФИЛЬТРА.
        if (!highlightMatcher.matches(text))
            return;
        SmartMatchHighlight.appendMatchRanges(cell, highlightMatcher.getHighlightRanges(text));
    }

    @Override
    public String getText(Object element)
    {
        String baseText;
        if (base instanceof ILabelProvider)
            baseText = ((ILabelProvider) base).getText(element);
        else
        {
            Object text = Global.invoke(base, "getText", element); //$NON-NLS-1$
            baseText = text instanceof String ? (String) text : ""; //$NON-NLS-1$
        }
        String flat = resolveSubscriptionLabelOverride(element);
        return flat != null ? flat : baseText;
    }

    private String resolveSubscriptionLabelOverride(Object element)
    {
        if (bslFlatLabelSource == null || bslSubscriptionFlatLabels == null)
            return null;
        if (bslFlatFilter != null && bslFlatFilter.isFlattenWhenFiltered() && bslFlatFilter.isFiltering())
            return BslOutlineEventsSupport.resolveFlatDisplayLabel(element, bslFlatLabelSource,
                    bslSubscriptionFlatLabels);
        return BslOutlineEventsSupport.resolveTreeSubscriptionHandlerLabel(element, bslFlatLabelSource,
                bslSubscriptionFlatLabels);
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
