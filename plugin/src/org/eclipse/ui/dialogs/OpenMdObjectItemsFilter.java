package org.eclipse.ui.dialogs;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog.ItemsFilter;

import tormozit.MdTypeNames;
import tormozit.SmartMatcher;

public class OpenMdObjectItemsFilter extends FilteredItemsSelectionDialog.ItemsFilter {

    private SmartMatcher matcher;
    private final ILabelProvider labelProvider;
    private final FilteredItemsSelectionDialog dialog;

    public OpenMdObjectItemsFilter(FilteredItemsSelectionDialog dialog,
                                    ILabelProvider labelProvider,
                                    String pattern) {
        dialog.super();
        this.dialog = dialog;
        this.labelProvider = labelProvider;
        setPattern(pattern);
    }

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
        syncPatternMatcher();
    }

    private void syncPatternMatcher() {
        patternMatcher.setPattern(matcher.isEmpty ? "" : matcher.fullPattern);
    }

    public boolean shouldSkipSchedule(String pattern, Object currentFilter) {
        if (currentFilter == this) {
            return patternUnchanged(pattern);
        }
        if (currentFilter instanceof ItemsFilter) {
            String saved = patternMatcher.getPattern();
            applyPatternToMatcher(pattern);
            boolean same = equalsFilter((ItemsFilter) currentFilter);
            patternMatcher.setPattern(saved);
            return same;
        }
        return false;
    }

    @Override
    public String getPattern() {
        return patternMatcher.getPattern();
    }

    private boolean patternUnchanged(String pattern) {
        SmartMatcher next = new SmartMatcher(pattern);
        if (matcher.isEmpty != next.isEmpty) {
            return false;
        }
        return matcher.fullPattern.equals(next.fullPattern);
    }

    private void applyPatternToMatcher(String pattern) {
        SmartMatcher next = new SmartMatcher(pattern);
        patternMatcher.setPattern(next.isEmpty ? "" : next.fullPattern);
    }

    @Override
    public boolean isConsistentItem(Object item) {
        return true;
    }

    @Override
    public boolean matchItem(Object item) {
        if (matcher.isEmpty) {
            return isHistoryElement(item);
        }

        String objectName = getObjectName(labelProvider.getText(item));

        if (matcher.hasMultipleSections()) {
            String fullName = resolveFullNameFast(item);
            if (fullName != null)
                return matcher.matchesTree(fullName);
        }

        return matcher.matches(objectName);
    }

    public static String resolveFullNameFast(Object item) {
        if (item == null)
            return null;
        try {
            Object description = tormozit.Global.getField(item, "description");
            if (description == null)
                return null;
            String desc = description.toString();
            String result = MdTypeNames.translateDottedToRu(desc);
            return result != null && !result.isEmpty() ? result : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getObjectName(String fullText) {
        if (fullText == null) return "";
        int dashIdx = fullText.indexOf(" - ");
        return dashIdx >= 0 ? fullText.substring(0, dashIdx) : fullText;
    }

    private boolean isHistoryElement(Object item) {
        Object history = tormozit.Global.invoke(dialog, "getSelectionHistory");
        if (history == null)
            return false;
        return Boolean.TRUE.equals(tormozit.Global.invoke(history, "contains", item));
    }

    @Override
    public boolean isCamelCasePattern() {
        return false;
    }
}
