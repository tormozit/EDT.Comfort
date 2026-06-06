package tormozit;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;

public class OpenMdObjectLabelProvider extends LabelProvider implements IStyledLabelProvider, ILabelDecorator {

    private final ILabelProvider baseProvider;
    private final IStyledLabelProvider baseStyled;
    private final ILabelDecorator baseDecorator;
    private SmartMatcher matcher;

    public OpenMdObjectLabelProvider(ILabelProvider baseProvider,
                                      IStyledLabelProvider baseStyled,
                                      ILabelDecorator baseDecorator) {
        this.baseProvider = baseProvider;
        this.baseStyled = baseStyled;
        this.baseDecorator = baseDecorator;
        this.matcher = new SmartMatcher("");
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
        // === ПОДСВЕТКА ТОЛЬКО В ЧИСТОМ ИМЕНИ ===
        String objectName = org.eclipse.ui.dialogs.OpenMdObjectItemsFilter.getObjectName(plainText);
        int nameOffset = plainText.indexOf(objectName);
        if (nameOffset < 0) nameOffset = 0;

        for (SmartMatcher.HighlightRange range : matcher.getHighlightRanges(objectName)) {
            styledString.setStyle(nameOffset + range.offset, range.length, SmartMatchHighlight.styler());
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
        if (baseDecorator != null) baseDecorator.dispose();
        if (baseProvider != null) baseProvider.dispose();
        super.dispose();
    }
}