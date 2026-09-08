package tormozit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

/**
 * «Проверить» в панели «Ошибки конфигурации» — перезапускает проверки по текущей области отбора
 * панели (проект / объект / элемент), а не по всему проекту и не по объектам уже показанных ошибок.
 * <p>
 * Область берётся из самой панели: поле {@code scopeSelection}
 * ({@code com._1c.g5.v8.dt.internal.ui.validation.ScopeSelection}) — то же, по которому панель
 * строит отбор маркеров, поэтому перепроверяется ровно то, что панель показывает. Сам пересчёт —
 * {@link ComfortCheckRecompute}.
 * <p>
 * Если область — проект целиком (или отбор по подсистемам), конкретных объектов у панели нет;
 * такой случай не выполняется молча дорогой полной перепроверкой, а сообщается тостом:
 * для проверки всего проекта есть штатная команда EDT.
 * <p>
 * Кнопка живёт в собственной панели инструментов панели «Ошибки конфигурации», поэтому у обработчика
 * нет {@code activeWhen} по {@code activePartId}: иначе кнопка сереет, как только фокус уходит из
 * панели. Панель ищется явно через {@link IWorkbenchPage#findView}, а не через
 * {@link HandlerUtil#getActivePart}, который вне фокуса вернёт не тот part.
 */
public class ProblemViewRecomputeChecksHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        IViewPart part = page != null ? page.findView(ProblemViewMarkers.PROBLEM_VIEW_ID) : null;
        Object scopeSelection = Global.getField(part, "scopeSelection"); //$NON-NLS-1$
        Debug.log("команда вызвана: страница=" + (page != null) //$NON-NLS-1$
            + ", панель=" + (part == null ? "не найдена" : part.getClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
            + ", область=" + (scopeSelection == null ? "null" : scopeSelection.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
        if (scopeSelection == null)
            return null;

        Map<IProject, Set<EObject>> selectedObjects = nonEmptySelectedObjects(scopeSelection);
        Set<IProject> selectedProjects = selectedProjects(scopeSelection);
        logScope(selectedObjects, selectedProjects);
        if (selectedObjects.isEmpty() && selectedProjects.isEmpty())
        {
            toast("Проверить", //$NON-NLS-1$
                "В текущей области отбора панели нет ни объектов, ни проекта — проверять нечего.");
            return null;
        }

        for (Map.Entry<IProject, Set<EObject>> entry : selectedObjects.entrySet())
        {
            if (isWholeProject(entry.getValue()))
                ComfortCheckRecompute.recomputeProject(entry.getKey());
            else
                ComfortCheckRecompute.recomputeObjects(entry.getKey(), entry.getValue());
        }

        // Область — проект целиком: конкретных объектов у панели нет
        for (IProject project : selectedProjects)
        {
            if (!selectedObjects.containsKey(project))
                ComfortCheckRecompute.recomputeProject(project);
        }
        return null;
    }

    /** Что панель кладёт в область отбора — в журнал «Комфорт». */
    private static void logScope(Map<IProject, Set<EObject>> objects, Set<IProject> projects)
    {
        if (!Global.isLogEnabled())
            return;
        StringBuilder text = new StringBuilder("область команды: проекты="); //$NON-NLS-1$
        for (IProject project : projects)
            text.append(project.getName()).append(' ');
        text.append("| объекты="); //$NON-NLS-1$
        if (objects.isEmpty())
            text.append("нет"); //$NON-NLS-1$
        for (Map.Entry<IProject, Set<EObject>> entry : objects.entrySet())
        {
            text.append(entry.getKey().getName()).append(": "); //$NON-NLS-1$
            for (EObject object : entry.getValue())
            {
                text.append(object == null ? "null" : object.getClass().getName()) //$NON-NLS-1$
                    .append(object instanceof Configuration ? " (Configuration)" : "") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(' ');
            }
        }
        Debug.log(text.toString());
    }

    /** Журнал «Комфорт» — при включённом флажке «Вести журнал». */
    private static final class Debug
    {
        private static final String TAG = "CheckCommand"; //$NON-NLS-1$

        private Debug()
        {
        }

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }

    /**
     * Выбран корневой узел проекта: в области отбора стоит сама конфигурация. Точечная
     * перепроверка такого объекта отрабатывает мгновенно и не проверяет ничего — на корне нужна
     * полная проверка всех объектов проекта.
     */
    private static boolean isWholeProject(Set<EObject> objects)
    {
        for (EObject object : objects)
        {
            if (object instanceof Configuration)
                return true;
        }
        return false;
    }

    /** Проекты области отбора панели. */
    private static Set<IProject> selectedProjects(Object scopeSelection)
    {
        Object result = Global.invoke(scopeSelection, "getSelectedProjects"); //$NON-NLS-1$
        if (!(result instanceof Set<?> raw) || raw.isEmpty())
            return Set.of();

        Set<IProject> projects = new LinkedHashSet<>();
        for (Object item : raw)
        {
            if (item instanceof IProject project)
                projects.add(project);
        }
        return projects;
    }

    /**
     * Объекты области отбора без пустых Set: EDT иногда кладёт в map проект с пустым набором
     * ({@code putIfAbsent}), и тогда «непустая» map молча ничего не перепроверяла.
     */
    @SuppressWarnings("unchecked")
    private static Map<IProject, Set<EObject>> nonEmptySelectedObjects(Object scopeSelection)
    {
        Object result = Global.invoke(scopeSelection, "getSelectedObjects"); //$NON-NLS-1$
        if (!(result instanceof Map<?, ?> raw) || raw.isEmpty())
            return Map.of();

        Map<IProject, Set<EObject>> filtered = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet())
        {
            if (!(entry.getKey() instanceof IProject project))
                continue;
            if (!(entry.getValue() instanceof Set<?> set) || set.isEmpty())
                continue;
            filtered.put(project, (Set<EObject>)set);
        }
        return filtered;
    }

    private static void toast(String title, String message)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, 5_000));
    }
}
