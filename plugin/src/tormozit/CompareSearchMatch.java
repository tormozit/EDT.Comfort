package tormozit;

public class CompareSearchMatch
{
    final Object comparisonNode;
    final String objectPath;
    final String propertyName;
    final String columnSide;
    final String matchText;
    final String comparisonStatus;
    final boolean checkable;

    public CompareSearchMatch(Object comparisonNode, String objectPath, String propertyName, String columnSide, String matchText, String comparisonStatus, boolean checkable)
    {
        this.comparisonNode = comparisonNode;
        this.objectPath = objectPath;
        this.propertyName = propertyName;
        this.columnSide = columnSide;
        this.matchText = matchText;
        this.comparisonStatus = comparisonStatus;
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

    public boolean isCheckable()
    {
        return checkable;
    }
}
