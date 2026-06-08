package tormozit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.TextStyle;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Серый квалификатор справа от имени (до git-суффикса {@code [...]}). */
final class NavigatorLabelQualifier
{
    private NavigatorLabelQualifier() {}

    static void applyToCell(ViewerCell cell, Object element, NavigatorFuzzySearch.QualifierMatch qualifier,
            SmartMatcher matcher)
    {
        if (cell == null || qualifier == null || matcher == null)
            return;
        String text = cell.getText();
        if (text == null || text.isEmpty())
            return;

        int insertAt = insertOffset(text, element);
        String insertion = "  " + qualifier.text; //$NON-NLS-1$
        String newText = text.substring(0, insertAt) + insertion + text.substring(insertAt);

        List<StyleRange> ranges = shiftStyleRanges(cell.getStyleRanges(), insertAt, insertion.length());
        ranges.addAll(offsetStyleRanges(qualifierStyleRanges(insertion), insertAt));
        ranges.sort(Comparator.comparingInt(r -> r.start));

        cell.setText(newText);
        if (!ranges.isEmpty())
            cell.setStyleRanges(ranges.toArray(new StyleRange[0]));

        SmartMatchHighlight.appendMatchRanges(cell, matcher.getHighlightRanges(newText));
    }

    static StyledString applyToStyledString(StyledString styled, Object element,
            NavigatorFuzzySearch.QualifierMatch qualifier, SmartMatcher matcher)
    {
        if (styled == null || qualifier == null || matcher == null)
            return styled != null ? styled : new StyledString();

        String text = styled.getString();
        if (text == null || text.isEmpty())
            return styled;

        int insertAt = insertOffset(text, element);
        String insertion = "  " + qualifier.text; //$NON-NLS-1$
        String newText = text.substring(0, insertAt) + insertion + text.substring(insertAt);

        List<StyleRange> ranges = shiftStyleRanges(styled.getStyleRanges(), insertAt, insertion.length());
        ranges.addAll(offsetStyleRanges(qualifierStyleRanges(insertion), insertAt));
        ranges.sort(Comparator.comparingInt(r -> r.start));

        StyledString result = new StyledString(newText);
        applyStyleRanges(result, ranges);
        SmartMatchHighlight.applyRanges(result, matcher.getHighlightRanges(newText));
        return result;
    }

    private static void applyStyleRanges(StyledString styled, List<StyleRange> ranges)
    {
        if (styled == null || ranges == null)
            return;
        String text = styled.getString();
        for (StyleRange range : ranges)
        {
            if (range == null || range.length <= 0 || range.start < 0
                    || range.start + range.length > text.length())
                continue;
            styled.setStyle(range.start, range.length, styleRangeStyler(range));
        }
    }

    private static Styler styleRangeStyler(StyleRange range)
    {
        return new Styler()
        {
            @Override
            public void applyStyles(TextStyle textStyle)
            {
                textStyle.foreground = range.foreground;
                textStyle.background = range.background;
                textStyle.font = range.font;
            }
        };
    }

    /** Вставка квалификатора до штатного суффикса (git в {@code [...]}). */
    static int insertOffset(String text, Object element)
    {
        if (text == null || text.isEmpty())
            return 0;

        int bracket = text.indexOf(" ["); //$NON-NLS-1$
        if (bracket > 0)
            return bracket;

        MdObject mdObject = NavigatorTreeElementLabels.resolveMdObject(element);
        if (mdObject != null)
        {
            String name = mdObject.getName();
            if (name != null && !name.isEmpty() && text.startsWith(name))
                return name.length();
        }
        return text.length();
    }

    private static List<StyleRange> shiftStyleRanges(StyleRange[] nativeRanges, int insertAt, int shift)
    {
        List<StyleRange> ranges = new ArrayList<>();
        if (nativeRanges == null)
            return ranges;

        for (StyleRange source : nativeRanges)
        {
            if (source == null)
                continue;
            StyleRange copy = (StyleRange) source.clone();
            if (copy.start >= insertAt)
                copy.start += shift;
            ranges.add(copy);
        }
        return ranges;
    }

    private static StyleRange[] qualifierStyleRanges(String insertion)
    {
        StyledString styled = new StyledString();
        styled.append(insertion, StyledString.QUALIFIER_STYLER);
        StyleRange[] ranges = styled.getStyleRanges();
        return ranges != null ? ranges : new StyleRange[0];
    }

    private static List<StyleRange> offsetStyleRanges(StyleRange[] ranges, int offset)
    {
        List<StyleRange> result = new ArrayList<>();
        if (ranges == null)
            return result;
        for (StyleRange source : ranges)
        {
            if (source == null)
                continue;
            StyleRange copy = (StyleRange) source.clone();
            copy.start += offset;
            result.add(copy);
        }
        return result;
    }
}
