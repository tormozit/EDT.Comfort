package org.eclipse.ui.dialogs;

import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog.ItemsFilter;

import tormozit.SmartMatcher;

/**
 * Многословный AND-фильтр ({@link SmartMatcher}) для {@code BslExceptionSelectionDialog}
 * («Остановка по ошибке») — список плоский (строки сообщений исключений), без иерархии.
 * В том же пакете, что {@link FilteredItemsSelectionDialog}, — доступ к {@code ItemsFilter}
 * и его защищённому {@code patternMatcher} без рефлексии (как {@link OpenMdObjectItemsFilter}).
 */
public class BslExceptionSmartFilter extends FilteredItemsSelectionDialog.ItemsFilter
{
    private SmartMatcher matcher;

    public BslExceptionSmartFilter(FilteredItemsSelectionDialog dialog, String pattern)
    {
        dialog.super();
        setPattern(pattern);
    }

    public void setPattern(String pattern)
    {
        matcher = new SmartMatcher(pattern);
        patternMatcher.setPattern(matcher.isEmpty ? "" : matcher.fullPattern);
    }

    @Override
    public String getPattern()
    {
        return patternMatcher.getPattern();
    }

    public boolean shouldSkipSchedule(String pattern, Object currentFilter)
    {
        if (currentFilter == this)
            return patternUnchanged(pattern);
        if (currentFilter instanceof ItemsFilter other)
        {
            String saved = patternMatcher.getPattern();
            applyPatternToMatcher(pattern);
            boolean same = equalsFilter(other);
            patternMatcher.setPattern(saved);
            return same;
        }
        return false;
    }

    private boolean patternUnchanged(String pattern)
    {
        SmartMatcher next = new SmartMatcher(pattern);
        if (matcher.isEmpty != next.isEmpty)
            return false;
        return matcher.fullPattern.equals(next.fullPattern);
    }

    private void applyPatternToMatcher(String pattern)
    {
        SmartMatcher next = new SmartMatcher(pattern);
        patternMatcher.setPattern(next.isEmpty ? "" : next.fullPattern);
    }

    @Override
    public boolean isConsistentItem(Object item)
    {
        return true;
    }

    @Override
    public boolean matchItem(Object item)
    {
        if (matcher.isEmpty)
            return true;
        return matcher.matches(String.valueOf(item));
    }

    @Override
    public boolean isCamelCasePattern()
    {
        return false;
    }
}
