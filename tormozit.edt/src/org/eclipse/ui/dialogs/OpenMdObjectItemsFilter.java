package org.eclipse.ui.dialogs;

import java.util.List;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
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
        this.matcher = new SmartMatcher(pattern);
    }

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
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

        String text = labelProvider.getText(item);
        if (text != null && matcher.matches(text)) {
            return true;
        }
        try {
            String className = item.getClass().getName();
            if (className.contains("ObjectDescriptionPair")) {
                Object desc = Global.getField(item, "description");
                if (desc != null && matchesQualifiedName(desc)) return true;

                Object descRu = Global.getField(item, "descriptionRu");
                if (descRu != null && matchesQualifiedName(descRu)) return true;
            }
        } catch (Exception ignored) {}
        return false;
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

    private boolean matchesQualifiedName(Object description) throws Exception {
        Object qName = Global.invoke(description, "getQualifiedName");
        return qName != null && matcher.matches(qName.toString());
    }

    @Override
    public String getPattern() {
        return matcher.isEmpty ? " " : matcher.fullPattern;
    }

    @Override
    public boolean isSubFilter(FilteredItemsSelectionDialog.ItemsFilter filter) {
        return false;
    }

    @Override
    public boolean isCamelCasePattern() {
        return false;
    }
}