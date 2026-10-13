package tormozit;

import java.util.List;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultListener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.core.resources.IProject;

public class CompareSearchResult implements ISearchResult
{
    private CompareSearchQuery query;
    private final List<CompareSearchMatch> matches;
    private final IEditorPart editorPart;
    private final IProject project;
    private String queryText;
    private String scopeLabel;
    private boolean spelling;
    private boolean allReferences;

    /** Поиск всех ссылок: результаты не предлагают удаление битых ссылок. */
    public void setAllReferences(boolean allReferences) { this.allReferences = allReferences; }

    public boolean isAllReferences() { return allReferences; }

    /** Режим «Ошибки орфографии»: дерево слева — объекты с ошибками, строки ведут в свойство или модуль. */
    public void setSpelling(boolean spelling) { this.spelling = spelling; }

    public boolean isSpelling() { return spelling; }

    private boolean allTexts;

    /**
     * Режим «Ошибки орфографии» по всем текстам: кроме текстов для пользователя проверены программные
     * (имена, комментарии, строки модулей) — в таблице есть колонка «Пользовательский».
     */
    public void setAllTexts(boolean allTexts) { this.allTexts = allTexts; }

    public boolean isAllTexts() { return allTexts; }

    public CompareSearchResult(List<CompareSearchMatch> matches, IEditorPart editorPart)
    {
        this(matches, editorPart, null);
    }

    public CompareSearchResult(List<CompareSearchMatch> matches, IEditorPart editorPart, IProject project)
    {
        this.matches = matches;
        this.editorPart = editorPart;
        this.project = project;
    }

    public IProject getProject() { return project; }

    /** Узлы проекта, внутри которых шёл поиск; {@code null} — искали во всём проекте. */
    public void setScopeLabel(String scopeLabel) { this.scopeLabel = scopeLabel; }

    public void setQuery(CompareSearchQuery query)
    {
        this.query = query;
    }

    public void setQueryText(String queryText)
    {
        this.queryText = queryText;
    }

    public String getQueryText()
    {
        return queryText;
    }

    public List<CompareSearchMatch> getMatches()
    {
        return matches;
    }

    public IEditorPart getEditorPart()
    {
        return editorPart;
    }

    /** Единый формат подписей глобального поиска — {@code "<строка>" в сравнении <представление> - <N> совпадений}. */
    @Override
    public String getLabel()
    {
        int count = matches != null ? matches.size() : 0;
        String q = queryText != null ? queryText.trim() : "";
        if (spelling)
            return "Ошибки орфографии " + (allTexts ? "во всех текстах " : "") + "в " + (scopeLabel != null ? scopeLabel + " проекта " : "проекте ")
                + project.getName() + " - " + count;
        if (project != null)
            return "'" + q + "' в " + (scopeLabel != null ? scopeLabel + " проекта " : "проекте ")
                + project.getName() + " - " + count + " совпадений";
        String comparisonTitle = editorPart != null ? editorPart.getTitle() : null;
        // Заголовок редактора сравнения сам начинается с "Сравнение/объединение (...)" — не дублируем
        // это слово после нашего "в сравнении", оставляем только скобочную часть (см. репорт: было
        // "в сравнении Сравнение/объединение (...)"; предыдущая попытка через regex.replaceFirst с
        // (?i) не сработала — (?i) без флага UNICODE_CASE не сворачивает регистр кириллицы).
        if (comparisonTitle != null)
        {
            int paren = comparisonTitle.indexOf('(');
            if (paren >= 0)
                comparisonTitle = comparisonTitle.substring(paren);
        }
        if (comparisonTitle == null || comparisonTitle.isEmpty())
            comparisonTitle = "конфигураций";
        String quoted = !q.isEmpty() ? "'" + q + "'" : ""; // одинарные кавычки — как в штатных поисках //$NON-NLS-1$ //$NON-NLS-2$
        return quoted + " в сравнении " + comparisonTitle + " - " + count + " совпадений";
    }

    @Override
    public String getTooltip()
    {
        return getLabel();
    }

    @Override
    public ImageDescriptor getImageDescriptor()
    {
        return null;
    }

    @Override
    public ISearchQuery getQuery()
    {
        return query;
    }

    @Override
    public void addListener(ISearchResultListener l)
    {
    }

    @Override
    public void removeListener(ISearchResultListener l)
    {
    }
}
