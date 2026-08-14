package tormozit;

import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Синтетический узел-группа общих модулей в навигаторе EDT (issue #117: группировка по
 * имени/суффиксу). Ничего не хранится на диске — создаётся заново {@link CommonModuleGroupContentProvider}
 * при каждом обращении к дереву, из {@link CommonModuleGrouping}.
 *
 * <p><b>{@link IAdaptable} обязателен, не косметика:</b> штатный {@code NavigatorSearchFilter.getProject(Object)}
 * (декомпиляция bundle {@code navigator-ui}) проверяет элемент по цепочке
 * {@code IProject → EObject → IAdaptable → TreePath} и, если ничего не подошло, бросает
 * {@code IllegalArgumentException} прямо во время {@code CommonViewer.refresh()} — без адаптера
 * это ронял рефреш дерева (issue #117, симптом «мигание/поломка при снятии текстового фильтра»).
 */
public final class CommonModuleGroupNode implements IAdaptable
{
    private final String baseName;
    private final List<CommonModule> members;
    private final Object parent;
    private final IProject project;
    /** Индекс самого верхнего участника в исходном порядке папки «Общие модули». */
    private final int sortIndex;

    public CommonModuleGroupNode(String baseName, List<CommonModule> members, Object parent, int sortIndex)
    {
        this.baseName = baseName;
        this.members = members;
        this.parent = parent;
        this.project = resolveProject();
        this.sortIndex = sortIndex;
    }

    public String getBaseName()
    {
        return baseName;
    }

    public List<CommonModule> getMembers()
    {
        return members;
    }

    public Object getParent()
    {
        return parent;
    }

    public int getSortIndex()
    {
        return sortIndex;
    }

    @Override
    public <T> T getAdapter(Class<T> adapter)
    {
        if (adapter == IProject.class)
            return project != null ? adapter.cast(project) : null;
        return null;
    }

    /** Проект первого участника группы — все участники одной папки «Общие модули», значит одного проекта. */
    private IProject resolveProject()
    {
        if (members.isEmpty())
            return null;
        IV8ProjectManager projectManager = Global.getOsgiService(IV8ProjectManager.class);
        if (projectManager == null)
            return null;
        IV8Project v8Project = projectManager.getProject(members.get(0));
        return v8Project != null ? v8Project.getProject() : null;
    }

    /**
     * Равенство — по (проект, базовое имя), НЕ по {@code parent}: EDT создаёт новый экземпляр
     * папки «Общие модули» на каждый запрос (не singleton, подтверждено диагностикой), поэтому
     * сравнение по {@code parent} никогда не совпадало бы между двумя {@code refresh()} — JFace
     * терял соответствие старых/новых элементов дерева (issue #117, симптом «фантомный скролл»
     * при клике после снятия текстового фильтра). {@link IProject} — стабильный, штатный
     * {@link org.eclipse.core.resources.IResource#equals} сравнивает по пути в воркспейсе.
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (!(obj instanceof CommonModuleGroupNode other))
            return false;
        return Objects.equals(baseName, other.baseName) && Objects.equals(project, other.project);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(baseName, project);
    }
}
