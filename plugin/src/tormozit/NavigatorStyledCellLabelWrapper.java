package tormozit;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Image;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Подсветка навигатора: без раскраски групп; у объектов — штатный серый квалификатор
 * с подсветкой вхождения в синониме/комментарии/подсказке.
 */
public final class NavigatorStyledCellLabelWrapper extends StyledCellLabelProvider
        implements SmartLabelHighlight, ILabelProvider
{
    private final StyledCellLabelProvider base;
    private final NavigatorSearchTextCache searchCache;
    private String highlightPattern = ""; //$NON-NLS-1$

    public NavigatorStyledCellLabelWrapper(StyledCellLabelProvider base)
    {
        this(base, null);
    }

    public NavigatorStyledCellLabelWrapper(StyledCellLabelProvider base, NavigatorSearchTextCache searchCache)
    {
        this.base = base;
        this.searchCache = searchCache;
    }

    @Override
    public void setHighlightPattern(String pattern)
    {
        highlightPattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        if (searchCache != null)
            searchCache.onPatternChanged(highlightPattern);
    }

    @Override
    public void update(ViewerCell cell)
    {
        if (cell == null)
            return;

        Object element = cell.getElement();
        base.update(cell);

        if (NavigatorTreeElementLabels.isGroupNode(element))
        {
            cell.setStyleRanges(null);
            return;
        }
        if (highlightPattern.isEmpty())
            return;

        StyledString styled = new StyledString(cell.getText() != null ? cell.getText() : ""); //$NON-NLS-1$
        decorateObjectLabel(styled, element);
        applyStyledString(cell, styled);
    }

    private void decorateObjectLabel(StyledString styled, Object element)
    {
        MdObject mdObject = NavigatorTreeElementLabels.resolveMdObject(element);
        if (mdObject == null || styled == null)
            return;

        String name = mdObject.getName() != null ? mdObject.getName() : ""; //$NON-NLS-1$

        NavigatorFuzzySearch.QualifierMatch qualifier = searchCache != null
                ? searchCache.qualifier(mdObject, highlightPattern, name)
                : NavigatorFuzzySearch.findQualifierMatch(mdObject, highlightPattern, name);
        if (qualifier != null && qualifier.text != null && !qualifier.text.isEmpty())
        {
            styled.append("  ", StyledString.QUALIFIER_STYLER); //$NON-NLS-1$
            styled.append(qualifier.text, StyledString.QUALIFIER_STYLER);
        }

        SmartMatcher matcher = new SmartMatcher(highlightPattern);
        String plain = styled.getString();
        if (matcher.matches(plain))
            SmartMatchHighlight.applyRanges(styled, matcher.getHighlightRanges(plain));
    }

    private static void applyStyledString(ViewerCell cell, StyledString styled)
    {
        if (cell == null || styled == null)
            return;
        cell.setText(styled.getString());
        StyleRange[] ranges = styled.getStyleRanges();
        if (ranges != null && ranges.length > 0)
            cell.setStyleRanges(ranges);
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
