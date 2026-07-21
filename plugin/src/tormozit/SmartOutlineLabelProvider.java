package tormozit;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import java.util.function.Function;
import java.util.function.Predicate;

public class SmartOutlineLabelProvider extends LabelProvider implements IStyledLabelProvider, SmartLabelHighlight {

    private final IStyledLabelProvider baseStyled;
    private final ILabelProvider basePlain;
    private final Object imageSource;
    private final Function<Object, String> matchTextFn;
    private final Predicate<Object> skipHighlight;
    private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$
    public SmartOutlineLabelProvider(IStyledLabelProvider baseStyled) {

        this(baseStyled, null);
    }

    public SmartOutlineLabelProvider(IStyledLabelProvider baseStyled, ILabelProvider basePlain) {

        this(baseStyled, basePlain, null);
    }

    public SmartOutlineLabelProvider(IStyledLabelProvider baseStyled, ILabelProvider basePlain,
            Predicate<Object> skipHighlight) {

        this(baseStyled, basePlain, skipHighlight, null);
    }

    public SmartOutlineLabelProvider(IStyledLabelProvider baseStyled, ILabelProvider basePlain,
            Predicate<Object> skipHighlight, Object imageSource) {

        this(baseStyled, basePlain, skipHighlight, imageSource, null);
    }

    public SmartOutlineLabelProvider(IStyledLabelProvider baseStyled, ILabelProvider basePlain,
            Predicate<Object> skipHighlight, Object imageSource, Function<Object, String> matchTextFn) {

        this.baseStyled = baseStyled;
        this.basePlain = basePlain;
        this.imageSource = imageSource;
        this.matchTextFn = matchTextFn;
        this.skipHighlight = skipHighlight;
    }

    @Override
    public void setHighlightPattern(String pattern) {

        highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
    }

    /** @deprecated use {@link #setHighlightPattern} */
    public void setPattern(String pattern) {

        setHighlightPattern(pattern);
    }

    @Override
    public StyledString getStyledText(Object element) {

        Global.tempLog("QConstructorFilter", "getStyledText element=" //$NON-NLS-1$ //$NON-NLS-2$
            + (element != null ? element.getClass().getSimpleName() : "null") //$NON-NLS-1$
            + " highlightPattern=\"" + highlightMatcher.fullPattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        StyledString styledString = obtainBaseStyledText(element);
        applyHighlightIfNeeded(element, styledString);
        return styledString;
    }

    protected StyledString obtainBaseStyledText(Object element)
    {
        if (baseStyled != null)
        {
            StyledString styledString = baseStyled.getStyledText(element);
            return styledString != null ? styledString : new StyledString();
        }
        if (basePlain != null)
        {
            String text = basePlain.getText(element);
            return new StyledString(text != null ? text : ""); //$NON-NLS-1$
        }
        return new StyledString();
    }

    protected void applyHighlightIfNeeded(Object element, StyledString styledString)
    {
        // НЕ ТРОГАТЬ ГРУППЫ (навигатор): skipHighlight = NavigatorTreeElementLabels::isGroupNode
        if (styledString == null || highlightMatcher.isEmpty
                || (skipHighlight != null && skipHighlight.test(element)))
        {
            Global.tempLog("QConstructorFilter", "applyHighlightIfNeeded SKIP styledNull=" //$NON-NLS-1$ //$NON-NLS-2$
                + (styledString == null) + " empty=" + highlightMatcher.isEmpty //$NON-NLS-1$
                + " skip=" + (skipHighlight != null && skipHighlight.test(element))); //$NON-NLS-1$
            return;
        }
        String plainText = styledString.getString();
        if (highlightMatcher.hasMultipleSections())
        {
            // Секционный фильтр: разные строки дерева несут разные секции (родитель/потомок) —
            // требовать совпадение ВСЕХ фрагментов на одной строке нельзя, красим то, что нашлось.
            java.util.List<SmartMatcher.HighlightRange> ranges = highlightMatcher.getHighlightRanges(plainText);
            Global.tempLog("QConstructorFilter", "applyHighlightIfNeeded multiSection text=\"" + plainText //$NON-NLS-1$ //$NON-NLS-2$
                + "\" ranges=" + ranges.size()); //$NON-NLS-1$
            if (!ranges.isEmpty())
                SmartMatchHighlight.applyRanges(styledString, ranges);
            return;
        }
        String matchText = resolveMatchText(element, plainText);
        boolean matches = highlightMatcher.matches(matchText);
        java.util.List<SmartMatcher.HighlightRange> ranges = highlightMatcher.getHighlightRanges(plainText);
        Global.tempLog("QConstructorFilter", "applyHighlightIfNeeded text=\"" + plainText + "\" matchText=\"" //$NON-NLS-1$ //$NON-NLS-2$
            + matchText + "\" matches=" + matches + " ranges=" + ranges.size()); //$NON-NLS-1$ //$NON-NLS-2$
        if (matches)
            SmartMatchHighlight.applyRanges(styledString, ranges);
    }

    protected String resolveMatchText(Object element, String plainText)
    {
        if (matchTextFn != null)
            return matchTextFn.apply(element);
        if (basePlain != null)
            return basePlain.getText(element);
        return plainText;
    }

    @Override
    public Image getImage(Object element) {

        // basePlain (filterLabels) — только текст поиска; getImage через него даёт цикл с Decorating.
        if (imageSource != null)
            return resolveLabelImage(imageSource, element);
        if (baseStyled != null)
        {
            Image fromStyled = resolveLabelImage(baseStyled, element);
            if (fromStyled != null)
                return fromStyled;
        }
        if (basePlain != null)
            return resolveLabelImage(basePlain, element);
        return null;
    }

    private static Image resolveLabelImage(Object source, Object element)
    {
        if (source == null)
            return null;
        if (source instanceof ILabelProvider)
            return ((ILabelProvider) source).getImage(element);
        Object img = Global.invoke(source, "getImage", element); //$NON-NLS-1$
        return img instanceof Image ? (Image) img : null;
    }

    @Override
    public void dispose() {

        if (baseStyled != null)
            baseStyled.dispose();
        super.dispose();
    }

}

