package tormozit;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerSorter;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Алфавитная сортировка узлов-групп общих модулей вперемешку с обычными модулями (issue #117).
 *
 * <p>Без этого класса сравнение элементов из РАЗНЫХ навигаторных расширений (наше расширение
 * группировки vs штатное {@code com._1c.g5.v8.dt.navigator.ui.v8model}) делегируется штатному
 * {@code com._1c.g5.v8.dt.navigator.ui.NavigatorSorter} EDT (декомпиляция bundle {@code navigator-ui}
 * подтвердила: его {@code compare()} безусловно возвращает 0, реального сравнения нет — порядок
 * определяется исключительно тем, в каком порядке слились результаты content provider'ов).
 * Группы поэтому шли отдельным блоком, а не по алфавиту вперемешку с модулями.
 */
public final class CommonModuleGroupSorter extends ViewerSorter
{
    @Override
    public int compare(Viewer viewer, Object e1, Object e2)
    {
        return getComparator().compare(labelOf(e1), labelOf(e2));
    }

    private static String labelOf(Object element)
    {
        if (element instanceof CommonModuleGroupNode group)
            return group.getBaseName();
        if (element instanceof CommonModule module)
            return module.getName();
        return String.valueOf(element);
    }
}
