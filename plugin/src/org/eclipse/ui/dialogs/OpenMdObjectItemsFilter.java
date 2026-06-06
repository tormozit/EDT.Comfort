package org.eclipse.ui.dialogs;

import java.util.List;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog.ItemsFilter;

import tormozit.Global;
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

    /**
     * Нужно ли пропустить перезапуск jobs (как {@code applyFilter} + {@code equalsFilter} в EDT).
     * Вызывать <b>до</b> {@link #setPattern(String)} — иначе при {@code currentFilter == this}
     * сравнение всегда true и фильтр замирает после первого символа.
     */
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
        return matcher.matches(objectName);
    }

    /**
     * Извлекает чистое имя объекта из полного текста (до " - ").
     * Единая точка правды для фильтрации и подсветки.
     */
    public static String getObjectName(String fullText) {
        if (fullText == null) return "";
        int dashIdx = fullText.indexOf(" - ");
        return dashIdx >= 0 ? fullText.substring(0, dashIdx) : fullText;
    }

    private boolean isHistoryElement(Object item) {
        Object history = Global.invoke(dialog, "getSelectionHistory");
        if (history == null) return false;
        List<?> historyItems = (List<?>) Global.getField(history, "items");
        if (historyItems == null) return false;

        for (Object historyItem : historyItems) {
            if (isSameItem(item, historyItem)) return true;
        }
        return false;
    }

    private boolean isSameItem(Object a, Object b) {
        if (a == b) return true;
        Object descA = Global.getField(a, "description");
        Object descB = Global.getField(b, "description");
        if (descA == null || descB == null) return false;
        Object uriA = Global.invoke(descA, "getEObjectURI");
        Object uriB = Global.invoke(descB, "getEObjectURI");
        return uriA != null && uriA.equals(uriB);
    }

    @Override
    public boolean isCamelCasePattern() {
        return false;
    }
}
