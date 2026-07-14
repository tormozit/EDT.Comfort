package tormozit;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.graphics.Image;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Подсветка навигатора поверх штатного {@link StyledCellLabelProvider} EDT:
 * иконки, уголок изменений, git-суффикс в скобках — из {@code base.update(cell)}.
 * При пустом фильтре ячейка не меняется; при поиске — только overlay подсветки
 * и при необходимости серый квалификатор до динамических суффиксов.
 *
 * <p>НЕ ТРОГАТЬ ГРУППЫ: {@link NavigatorTreeElementLabels#isGroupNode(Object)} — без подсветки
 * и без пересборки надписи (только штатный {@code base.update}).
 */
public final class NavigatorStyledCellLabelWrapper extends StyledCellLabelProvider
        implements SmartLabelHighlight, ILabelProvider
{
    private final StyledCellLabelProvider base;
    private final NavigatorSearchTextCache searchCache;
    private final Object labelSource;
    private String highlightPattern = ""; //$NON-NLS-1$

    public NavigatorStyledCellLabelWrapper(StyledCellLabelProvider base)
    {
        this(base, null, null);
    }

    public NavigatorStyledCellLabelWrapper(StyledCellLabelProvider base, NavigatorSearchTextCache searchCache)
    {
        this(base, searchCache, null);
    }

    public NavigatorStyledCellLabelWrapper(StyledCellLabelProvider base, NavigatorSearchTextCache searchCache,
            Object labelSource)
    {
        this.base = base;
        this.searchCache = searchCache;
        this.labelSource = labelSource;
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

        // НЕ ТРОГАТЬ ГРУППЫ: служебные папки EDT — только штатная отрисовка
        if (highlightPattern.isEmpty() || NavigatorTreeElementLabels.isGroupNode(element))
            return;

        String text = cell.getText();
        if (text == null || text.isEmpty())
            return;

        SmartMatcher matcher = new SmartMatcher(highlightPattern);

        if (matcher.hasMultipleSections())
        {
            // Секционный фильтр ("справ.вал"): у родителя и потомка в дереве — разные секции,
            // ПОЛНОЕ совпадение всех слов на одной строке (как ниже) требовать нельзя.
            java.util.List<SmartMatcher.HighlightRange> ranges = matcher.getHighlightRanges(text);
            if (!ranges.isEmpty())
                SmartMatchHighlight.appendMatchRanges(cell, ranges);
            return;
        }

        // ПОДСВЕТКА ТОЛЬКО ПРИ ПОЛНОМ СОВПАДЕНИИ ВСЕХ СЛОВ ФИЛЬТРА
        // ПО ПОЛНОМУ ПОИСКОВОМУ ТЕКСТУ ОБЪЕКТА (ИМЯ+СИНОНИМ+КОММЕНТАРИЙ+TOOLTIP).
        if (!matchesFullFilter(element, matcher, text))
            return;

        NavigatorFuzzySearch.QualifierMatch qualifier = resolveQualifier(element);
        if (qualifier == null)
        {
            SmartMatchHighlight.appendMatchRanges(cell, matcher.getHighlightRanges(text));
            return;
        }

        NavigatorLabelQualifier.applyToCell(cell, element, qualifier, matcher);
    }

    private NavigatorFuzzySearch.QualifierMatch resolveQualifier(Object element)
    {
        MdObject mdObject = NavigatorTreeElementLabels.resolveMdObject(element);
        if (mdObject == null)
            return null;

        String name = mdObject.getName() != null ? mdObject.getName() : ""; //$NON-NLS-1$
        return searchCache != null
                ? searchCache.qualifier(mdObject, highlightPattern, name)
                : NavigatorFuzzySearch.findQualifierMatch(mdObject, highlightPattern, name);
    }

    private boolean matchesFullFilter(Object element, SmartMatcher matcher, String fallbackText)
    {
        if (matcher == null || matcher.isEmpty)
            return false;
        // ФИЛЬТРАЦИЯ НЕ ДОЛЖНА БЛОКИРОВАТЬ ВВОД:
        // используем кешированный searchText, без тяжелого пересчета EMF на каждый repaint.
        if (searchCache != null && labelSource != null)
        {
            String searchText = searchCache.searchText(element, labelSource);
            if (searchText != null && !searchText.isEmpty())
                return matcher.matches(searchText);
        }
        return matcher.matches(fallbackText);
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
