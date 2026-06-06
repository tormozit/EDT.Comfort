package tormozit;
import java.util.Map;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;

public class SmartOutlineComparator extends ViewerComparator {

    private final ILabelProvider labelProvider;
    private Map<Object, Integer> namePremiumCache;
    private Map<Object, Integer> paramPremiumCache;
    private SmartMatcher matcher;

    public SmartOutlineComparator(Map<Object, Integer> namePremiumCache, Map<Object, Integer> paramPremiumCache, ILabelProvider labelProvider) {
        this.namePremiumCache = namePremiumCache;
        this.paramPremiumCache = paramPremiumCache;
        this.labelProvider = labelProvider;
    }

    public SmartOutlineComparator(SmartMatcher matcher, ILabelProvider labelProvider) {
        this.matcher = matcher;
        this.labelProvider = labelProvider;
    }

    @Override
    public int compare(Viewer viewer, Object e1, Object e2) {
        int np1 = 0, np2 = 0;
        int pp1 = 0, pp2 = 0;

        if (namePremiumCache != null && paramPremiumCache != null) {
            np1 = namePremiumCache.getOrDefault(e1, 0);
            np2 = namePremiumCache.getOrDefault(e2, 0);
            pp1 = paramPremiumCache.getOrDefault(e1, 0);
            pp2 = paramPremiumCache.getOrDefault(e2, 0);
        } else if (matcher != null) {
            String t1 = labelProvider.getText(e1);
            String t2 = labelProvider.getText(e2);
            np1 = matcher.computeNamePremium(t1);
            np2 = matcher.computeNamePremium(t2);
            pp1 = matcher.computeParamPremium(t1);
            pp2 = matcher.computeParamPremium(t2);
        }

        // ПРИОРИТЕТ 1: Рейтинг Имени (от большего к меньшему)
        if (np1 != np2) {
            return Integer.compare(np2, np1);
        }

        // ПРИОРИТЕТ 2: Рейтинг Параметров (от большего к меньшему)
        if (pp1 != pp2) {
            return Integer.compare(pp2, pp1);
        }

        // ПРИОРИТЕТ 3: Алфавитный порядок (без учета регистра)
        String t1 = labelProvider.getText(e1);
        String t2 = labelProvider.getText(e2);
        
        if (t1 == null) return t2 == null ? 0 : 1;
        if (t2 == null) return -1;
        
        return t1.compareToIgnoreCase(t2);
    }
}