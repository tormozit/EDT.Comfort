package tormozit;

public class CompareSearchMatch
{
    /** Согласован с {@link #comparisonStatus}: одно вычисление на поиске. */
    public enum RowColorKind
    {
        NONE,
        /** Только на MAIN → статус «Удалено», цвет onlyMain. */
        ONLY_MAIN,
        /** Только на OTHER → статус «Добавлено», цвет onlyOther. */
        ONLY_OTHER,
        /** Есть на обеих сторонах и отличается → «Изменено», цвет hasDiffs. */
        HAS_DIFFS
    }

    final Object comparisonNode;
    final String objectPath;
    final String propertyName;
    final String columnSide;
    final String matchText;
    final String comparisonStatus;
    final RowColorKind rowColorKind;
    final boolean checkable;

    public CompareSearchMatch(Object comparisonNode, String objectPath, String propertyName,
            String columnSide, String matchText, String comparisonStatus, RowColorKind rowColorKind,
            boolean checkable)
    {
        this.comparisonNode = comparisonNode;
        this.objectPath = objectPath;
        this.propertyName = propertyName;
        this.columnSide = columnSide;
        this.matchText = matchText;
        this.comparisonStatus = comparisonStatus;
        this.rowColorKind = rowColorKind != null ? rowColorKind : RowColorKind.NONE;
        this.checkable = checkable;
    }

    public Object getComparisonNode()
    {
        return comparisonNode;
    }

    public String getObjectPath()
    {
        return objectPath;
    }

    public String getPropertyName()
    {
        return propertyName;
    }

    public String getColumnSide()
    {
        return columnSide;
    }

    public String getMatchText()
    {
        return matchText;
    }

    public String getComparisonStatus()
    {
        return comparisonStatus;
    }

    public RowColorKind getRowColorKind()
    {
        return rowColorKind;
    }

    public boolean isCheckable()
    {
        return checkable;
    }
}
