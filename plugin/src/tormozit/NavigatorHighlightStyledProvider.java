package tormozit;

import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.StyledString;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Подсветка + серый квалификатор справа для inject-пути ({@code NavigatorDecoratingLabelProvider}).
 * Родной git-суффикс добавляет внешний Decorating после {@link #getStyledText}.
 */
final class NavigatorHighlightStyledProvider extends SmartOutlineLabelProvider
{
    private final NavigatorSearchTextCache searchCache;
    private String highlightPattern = ""; //$NON-NLS-1$

    NavigatorHighlightStyledProvider(IStyledLabelProvider baseStyled, ILabelProvider basePlain,
            Predicate<Object> skipHighlight, Object labelSource, Function<Object, String> matchTextFn,
            NavigatorSearchTextCache searchCache)
    {
        super(baseStyled, basePlain, skipHighlight, labelSource, matchTextFn);
        this.searchCache = searchCache;
    }

    @Override
    public void setHighlightPattern(String pattern)
    {
        highlightPattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        super.setHighlightPattern(highlightPattern);
        if (searchCache != null)
            searchCache.onPatternChanged(highlightPattern);
    }

    @Override
    public StyledString getStyledText(Object element)
    {
        StyledString styled = obtainBaseStyledText(element);
        if (highlightPattern.isEmpty() || NavigatorTreeElementLabels.isGroupNode(element))
            return styled;

        SmartMatcher matcher = new SmartMatcher(highlightPattern);
        String plainText = styled.getString();
        if (!matcher.matches(resolveMatchText(element, plainText)))
            return styled;

        NavigatorFuzzySearch.QualifierMatch qualifier = resolveQualifier(element);
        if (qualifier != null)
            return NavigatorLabelQualifier.applyToStyledString(styled, element, qualifier, matcher);

        applyHighlightIfNeeded(element, styled);
        return styled;
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
}
