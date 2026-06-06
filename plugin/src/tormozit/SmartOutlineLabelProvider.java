package tormozit;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;

public class SmartOutlineLabelProvider extends LabelProvider implements IStyledLabelProvider, SmartLabelHighlight {
    
    private final IStyledLabelProvider baseStyled;
    private final ILabelProvider basePlain;
    private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$
    
    public SmartOutlineLabelProvider(IStyledLabelProvider baseStyled) {
        this(baseStyled, null);
    }

    public SmartOutlineLabelProvider(IStyledLabelProvider baseStyled, ILabelProvider basePlain) {
        this.baseStyled = baseStyled;
        this.basePlain = basePlain;
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
        StyledString styledString;
        if (baseStyled != null) {
            styledString = baseStyled.getStyledText(element);
            if (styledString == null)
                styledString = new StyledString();
        }
        else if (basePlain != null) {
            String text = basePlain.getText(element);
            styledString = new StyledString(text != null ? text : ""); //$NON-NLS-1$
        }
        else {
            return new StyledString();
        }

        String plainText = styledString.getString();
        SmartMatchHighlight.applyRanges(styledString, highlightMatcher.getHighlightRanges(plainText));
        return styledString;
    }

    @Override
    public Image getImage(Object element) {
        if (basePlain != null)
            return basePlain.getImage(element);
        if (baseStyled instanceof LabelProvider)
            return ((LabelProvider) baseStyled).getImage(element);
        return null;
    }

    @Override
    public void dispose() {
        if (baseStyled != null)
            baseStyled.dispose();
        super.dispose();
    }
}
