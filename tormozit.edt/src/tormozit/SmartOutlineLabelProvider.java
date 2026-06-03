package tormozit;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;

public class SmartOutlineLabelProvider extends LabelProvider implements IStyledLabelProvider {
    
    private final IStyledLabelProvider baseProvider;
    private SmartMatcher matcher;
    
    public SmartOutlineLabelProvider(IStyledLabelProvider baseProvider) {
        this.baseProvider = baseProvider;
        this.matcher = new SmartMatcher("");
    }

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
    }

    @Override
    public StyledString getStyledText(Object element) {
        // Получаем базовую строку
        StyledString styledString = baseProvider.getStyledText(element);
        if (styledString == null) {
            return new StyledString();
        }
        
        String plainText = styledString.getString();

        // Накладываем стили подсветки нашего поиска поверх
        SmartMatchHighlight.applyRanges(styledString, matcher.getHighlightRanges(plainText));

        return styledString;
    }

    @Override
    public Image getImage(Object element) {
        if (baseProvider instanceof LabelProvider) {
            return ((LabelProvider) baseProvider).getImage(element);
        }
        return null;
    }

    /**
     * Обязательно уничтожаем созданный шрифт в операционной системе,
     * когда LabelProvider закрывается, чтобы 1C:EDT не текла по памяти.
     */
    @Override
    public void dispose() {
        if (baseProvider != null)
            baseProvider.dispose();
        super.dispose();
    }
}