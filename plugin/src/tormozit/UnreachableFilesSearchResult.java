package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultListener;
import org.eclipse.search.ui.NewSearchUI;

/**
 * Результат режима «Недостижимые файлы» панели «Поиск»: папки объектов метаданных, не связанные
 * с конфигурацией (см. {@link MdReachability}). Страница — {@link UnreachableFilesResultPage}.
 */
public class UnreachableFilesSearchResult implements ISearchResult
{
    private final IProject project;
    private final List<MdReachability.Item> items;
    private final String scope;
    private final ISearchQuery query = new Query();

    /** @param scope где шёл поиск внутри проекта; {@code null} — во всём проекте */
    public UnreachableFilesSearchResult(IProject project, List<MdReachability.Item> items, String scope)
    {
        this.project = project;
        this.items = new ArrayList<>(items);
        this.scope = scope;
    }

    private boolean indexed;

    public IProject getProject() { return project; }

    /** Строки найдены в индексе Git: «Подключить к родителю» добавляет файл родителя в индекс. */
    public boolean isIndexed() { return indexed; }

    public void setIndexed(boolean indexed) { this.indexed = indexed; }

    /** Изменяемый список: подключённые к конфигурации папки из него убираются. */
    public List<MdReachability.Item> getItems() { return items; }

    /**
     * Показывает результат в панели «Поиск».
     *
     * @param foreground дождаться показа: результат нужен до следующего за ним вопроса
     */
    public void show(boolean foreground)
    {
        if (!foreground)
        {
            NewSearchUI.runQueryInBackground(query);
            return;
        }
        IStatus status = NewSearchUI.runQueryInForeground(null, query);
        if (status == null || !status.isOK())
            throw new IllegalStateException("Не удалось показать недостижимые файлы в панели Поиск");
    }

    @Override
    public String getLabel()
    {
        return "Недостижимые файлы метаданных в " + (scope != null ? scope + " проекта " : "проекте ")
            + project.getName() + " - " + items.size() + " папок";
    }

    @Override
    public String getTooltip() { return getLabel(); }

    @Override
    public ImageDescriptor getImageDescriptor() { return null; }

    @Override
    public ISearchQuery getQuery() { return query; }

    @Override
    public void addListener(ISearchResultListener listener)
    {
    }

    @Override
    public void removeListener(ISearchResultListener listener)
    {
    }

    /** Поиск уже выполнен: запрос только несёт готовый результат в панель. */
    private final class Query implements ISearchQuery
    {
        @Override
        public IStatus run(IProgressMonitor monitor) { return Status.OK_STATUS; }

        @Override
        public String getLabel() { return UnreachableFilesSearchResult.this.getLabel(); }

        @Override
        public boolean canRerun() { return false; }

        @Override
        public boolean canRunInBackground() { return true; }

        @Override
        public ISearchResult getSearchResult() { return UnreachableFilesSearchResult.this; }
    }
}
