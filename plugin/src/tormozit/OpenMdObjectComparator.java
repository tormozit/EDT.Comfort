package tormozit;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jface.viewers.ILabelProvider;

public class OpenMdObjectComparator implements Comparator<Object> {

    private final ILabelProvider labelProvider;
    private SmartMatcher matcher;
    private final Map<Object, Integer> premiumCache = new HashMap<>();

    public OpenMdObjectComparator(ILabelProvider labelProvider) {
        this.labelProvider = labelProvider;
    }

    public void setMatcher(SmartMatcher matcher) {
        this.matcher = matcher;
        this.premiumCache.clear();
    }

    @Override
    public int compare(Object o1, Object o2) {
        if (matcher == null || matcher.fullPattern.isEmpty()) {
            return compareAlphabetically(o1, o2);
        }

        int p1 = premiumCache.computeIfAbsent(o1,
                k -> matcher.computeNamePremium(getObjectName(labelProvider.getText(k))));
        int p2 = premiumCache.computeIfAbsent(o2,
                k -> matcher.computeNamePremium(getObjectName(labelProvider.getText(k))));

        if (p1 != p2) {
            return Integer.compare(p2, p1);
        }
        return compareAlphabetically(o1, o2);
    }

    private String getObjectName(String fullText) {
        return org.eclipse.ui.dialogs.OpenMdObjectItemsFilter.getObjectName(fullText);
    }

    private int compareAlphabetically(Object o1, Object o2) {
        String t1 = labelProvider.getText(o1);
        String t2 = labelProvider.getText(o2);
        if (t1 == null) return t2 == null ? 0 : 1;
        if (t2 == null) return -1;
        return t1.compareToIgnoreCase(t2);
    }
}