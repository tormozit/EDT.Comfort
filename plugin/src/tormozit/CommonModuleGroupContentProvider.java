package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonContentProvider;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Добавляет виртуальные узлы-группы общих модулей как ДОПОЛНИТЕЛЬНЫЕ дети папки
 * «Общие модули» — штатное содержимое EDT не подменяется, а дополняется (Common Navigator
 * Framework, additive content extension). Ничего не хранится: группы пересчитываются
 * из {@link CommonModuleGrouping} при каждом обращении.
 *
 * <p>Механизм подтверждён рабочим прецедентом (DitriXNew/EDT-MCP, groups/ui) — попытка
 * перехвата через собственный {@code IAdapterFactory} результата не дала: рендер навигатора
 * EDT не читает лейблы через глобальный {@code AdapterManager}, а идёт через контент-сервис
 * CNF (композиция зарегистрированных {@code navigatorContent} расширений).
 */
public final class CommonModuleGroupContentProvider implements ICommonContentProvider
{
    private static final Object[] NO_CHILDREN = new Object[0];

    private Viewer viewer;

    @Override
    public void init(ICommonContentExtensionSite aConfig)
    {
    }

    @Override
    public Object[] getElements(Object inputElement)
    {
        return getChildren(inputElement);
    }

    @Override
    public Object[] getChildren(Object parentElement)
    {
        if (!isGroupingActive())
            return NO_CHILDREN;

        if (parentElement instanceof CommonModuleGroupNode group)
            return group.getMembers().toArray();

        if (isCommonModulesFolder(parentElement))
        {
            Map<String, List<CommonModule>> groups = groupsFor(parentElement);
            List<Object> nodes = new ArrayList<>(groups.size());
            for (Map.Entry<String, List<CommonModule>> entry : groups.entrySet())
                nodes.add(new CommonModuleGroupNode(entry.getKey(), entry.getValue(), parentElement));
            return nodes.toArray();
        }

        return NO_CHILDREN;
    }

    /**
     * Для сгруппированного модуля возвращает узел его группы (не {@code null}) — иначе JFace
     * при восстановлении выделения/раскрытия после перестроения дерева строит путь «Папка→Модуль»,
     * который не совпадает с реальным «Папка→Группа→Модуль», и выделение/раскрытие теряется
     * (issue #117: пропадает активная строка модуля после снятия текстового фильтра).
     */
    @Override
    public Object getParent(Object element)
    {
        if (element instanceof CommonModuleGroupNode group)
            return group.getParent();

        if (element instanceof CommonModule module && isGroupingActive())
        {
            Object folder = realParent(module);
            if (folder != null)
            {
                for (Map.Entry<String, List<CommonModule>> entry : groupsFor(folder).entrySet())
                    if (entry.getValue().contains(module))
                        return new CommonModuleGroupNode(entry.getKey(), entry.getValue(), folder);
            }
        }

        return null;
    }

    /** Реальный (штатный EDT) родитель элемента — через его собственный {@link IWorkbenchAdapter}. */
    private static Object realParent(Object element)
    {
        Object adapterObj = Platform.getAdapterManager().getAdapter(element, IWorkbenchAdapter.class);
        return adapterObj instanceof IWorkbenchAdapter wa ? wa.getParent(element) : null;
    }

    @Override
    public boolean hasChildren(Object element)
    {
        if (!isGroupingActive())
            return false;
        if (element instanceof CommonModuleGroupNode group)
            return !group.getMembers().isEmpty();
        if (isCommonModulesFolder(element))
            return !groupsFor(element).isEmpty();
        return false;
    }

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
    {
        this.viewer = viewer;
    }

    /**
     * Выключено флажком, либо временно приостановлено — пока активен умный фильтр (иначе поиск
     * не найдёт вложенные модули) или идёт переподключение фильтра по подсистемам после снятия
     * поиска (иначе виден мусорный неотфильтрованный промежуточный результат, issue #117).
     */
    private boolean isGroupingActive()
    {
        if (!ComfortSettings.isGroupCommonModulesEnabled())
            return false;
        Control control = viewer != null ? viewer.getControl() : null;
        if (!(control instanceof Tree tree))
            return true;
        return !NavigatorFilterHook.isSmartFilterPatternActive(tree)
                && !NavigatorFilterHook.isSubsystemsRebindPending(tree);
    }

    @Override
    public void dispose()
    {
    }

    @Override
    public void restoreState(IMemento aMemento)
    {
    }

    @Override
    public void saveState(IMemento aMemento)
    {
    }

    static boolean isCommonModulesFolder(Object element)
    {
        if (element == null)
            return false;
        String className = element.getClass().getName();
        return className.contains("CommonModuleNavigatorAdapter") && className.contains("Folder"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, List<CommonModule>> groupsFor(Object folderElement)
    {
        return CommonModuleGrouping.groupBySuffix(
                realCommonModules(folderElement), ComfortSettings.getGroupCommonModulesSuffixes());
    }

    /** Читает реальный (штатный EDT) список общих модулей папки — через её собственный {@link IWorkbenchAdapter}. */
    static List<CommonModule> realCommonModules(Object folderElement)
    {
        List<CommonModule> modules = new ArrayList<>();
        Object adapterObj = Platform.getAdapterManager().getAdapter(folderElement, IWorkbenchAdapter.class);
        if (!(adapterObj instanceof IWorkbenchAdapter wa))
            return modules;
        Object[] children = wa.getChildren(folderElement);
        if (children == null)
            return modules;
        for (Object child : children)
            if (child instanceof CommonModule cm)
                modules.add(cm);
        return modules;
    }
}
