package tormozit;

import org.eclipse.core.resources.IFile;

import com._1c.g5.v8.dt.search.core.text.TextSearchModelMatch;

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
    final String referenceFqn;
    private MdReferenceSupport.Location projectLocation;
    private TextSearchModelMatch modelMatch;
    private IFile moduleFile;
    private int moduleOffset;
    private String misspelledWord;

    public CompareSearchMatch(MdReferenceSupport.Location location, String path, String property, String text,
        String referenceFqn)
    {
        this(null, path, property, "Свойство проекта", text, "Битая ссылка", RowColorKind.NONE, false, referenceFqn);
        this.projectLocation = location;
    }

    /**
     * Ошибка орфографии в свойстве объекта проекта.
     *
     * @param modelMatch то же вхождение в виде результата поиска по конфигурации — им строка и
     *     открывается; {@code null} — перейти к слову нельзя
     */
    public static CompareSearchMatch misspelledProperty(TextSearchModelMatch modelMatch, String path,
        String property, String text, String word)
    {
        CompareSearchMatch match = new CompareSearchMatch(null, path, property, "Свойство проекта", text,
            "Ошибка орфографии", RowColorKind.NONE, false, null);
        match.modelMatch = modelMatch;
        match.misspelledWord = word;
        return match;
    }

    /** Ошибка орфографии в модуле: строка адресуется файлом и смещением слова в его тексте. */
    public static CompareSearchMatch misspelledInModule(IFile file, int offset, String path, String property,
        String text, String word)
    {
        CompareSearchMatch match = new CompareSearchMatch(null, path, property, "Модуль", text,
            "Ошибка орфографии", RowColorKind.NONE, false, null);
        match.moduleFile = file;
        match.moduleOffset = offset;
        match.misspelledWord = word;
        return match;
    }

    public MdReferenceSupport.Location getProjectLocation() { return projectLocation; }

    /** Вхождение слова с ошибкой орфографии в свойстве модели; {@code null} — строка другого вида. */
    public TextSearchModelMatch getModelMatch() { return modelMatch; }

    /** Модуль с ошибкой орфографии; {@code null} — строка относится к свойству модели. */
    public IFile getModuleFile() { return moduleFile; }

    public int getModuleOffset() { return moduleOffset; }

    /** Текст модуля выше слова изменился на {@code delta} символов (исправление другого слова). */
    void shiftModuleOffset(int delta) { moduleOffset += delta; }

    /** Слово с ошибкой орфографии; {@code null} — строка не из режима «Ошибки орфографии». */
    public String getMisspelledWord() { return misspelledWord; }

    public CompareSearchMatch(Object comparisonNode, String objectPath, String propertyName,
            String columnSide, String matchText, String comparisonStatus, RowColorKind rowColorKind,
            boolean checkable)
    {
        this(comparisonNode, objectPath, propertyName, columnSide, matchText, comparisonStatus,
            rowColorKind, checkable, null);
    }

    public CompareSearchMatch(Object comparisonNode, String objectPath, String propertyName,
            String columnSide, String matchText, String comparisonStatus, RowColorKind rowColorKind,
            boolean checkable, String referenceFqn)
    {
        this.comparisonNode = comparisonNode;
        this.objectPath = objectPath;
        this.propertyName = propertyName;
        this.columnSide = columnSide;
        this.matchText = matchText;
        this.comparisonStatus = comparisonStatus;
        this.rowColorKind = rowColorKind != null ? rowColorKind : RowColorKind.NONE;
        this.checkable = checkable;
        this.referenceFqn = referenceFqn;
    }

    public String getReferenceFqn()
    {
        return referenceFqn;
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
