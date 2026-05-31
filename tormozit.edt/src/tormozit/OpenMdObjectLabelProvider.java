package tormozit;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Display;

public class OpenMdObjectLabelProvider extends LabelProvider implements IStyledLabelProvider, ILabelDecorator {

    private final ILabelProvider baseProvider;
    private final IStyledLabelProvider baseStyled;
    private final ILabelDecorator baseDecorator;
    private SmartMatcher matcher;
    private Font boldFont;
    private final Styler matchStyler;

    public OpenMdObjectLabelProvider(ILabelProvider baseProvider,
                                      IStyledLabelProvider baseStyled,
                                      ILabelDecorator baseDecorator) {
        this.baseProvider = baseProvider;
        this.baseStyled = baseStyled;
        this.baseDecorator = baseDecorator;
        this.matcher = new SmartMatcher("");
        this.matchStyler = new Styler() {
            @Override
            public void applyStyles(TextStyle textStyle) {
                if (boldFont == null || boldFont.isDisposed()) {
                    Font defaultFont = JFaceResources.getDefaultFont();
                    FontData[] fontData = defaultFont.getFontData();
                    for (FontData fd : fontData) {
                        fd.setStyle(fd.getStyle() | SWT.BOLD);
                    }
                    boldFont = new Font(Display.getDefault(), fontData);
                }
                textStyle.font = boldFont;
                textStyle.foreground = Display.getDefault().getSystemColor(SWT.COLOR_DARK_RED);
            }
        };
    }

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
    }

    @Override
    public String getText(Object element) {
        return baseProvider != null ? baseProvider.getText(element) : super.getText(element);
    }

    @Override
    public Image getImage(Object element) {
        return baseProvider != null ? baseProvider.getImage(element) : super.getImage(element);
    }

    @Override
    public StyledString getStyledText(Object element) {
        StyledString styledString;
        if (baseStyled != null) {
            styledString = baseStyled.getStyledText(element);
            if (styledString == null) styledString = new StyledString(getText(element));
        } else {
            styledString = new StyledString(getText(element));
        }

        String plainText = styledString.getString();
        for (SmartMatcher.HighlightRange range : matcher.getHighlightRanges(plainText)) {
            styledString.setStyle(range.offset, range.length, matchStyler);
        }
        return styledString;
    }

    @Override
    public Image decorateImage(Image image, Object element) {
        return baseDecorator != null ? baseDecorator.decorateImage(image, element) : image;
    }

    @Override
    public String decorateText(String text, Object element) {
        return baseDecorator != null ? baseDecorator.decorateText(text, element) : text;
    }

    @Override
    public void dispose() {
        if (boldFont != null && !boldFont.isDisposed()) {
            boldFont.dispose();
        }
        if (baseDecorator != null) baseDecorator.dispose();
        if (baseProvider != null) baseProvider.dispose();
        super.dispose();
    }
}