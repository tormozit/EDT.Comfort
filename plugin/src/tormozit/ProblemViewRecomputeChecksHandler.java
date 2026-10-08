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
 * панели (проект / объект / элемент), а не по последнему выделению редактора.
 * <p>
 * Режим области берётся из настроек панели, а её источник — из {@code scopeSelection}.
 * Сам пересчёт — {@link ComfortCheckRecompute}.
 * <p>
 * Для областей «Все проекты» и «Фильтр по подсистемам» конкретный набор объектов здесь не определён;
 * команда предлагает выбрать область проекта, объекта или элемента.
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
        Object filters = problemFilters(part);
        Object scope = Global.invoke(filters, "getScope"); //$NON-NLS-1$
        String scopeName = scope instanceof Enum<?> value ? value.name() : null;
        boolean showAll = Boolean.TRUE.equals(Global.invoke(filters, "isShowAll")); //$NON-NLS-1$
        Debug.log("команда вызвана: страница=" + (page != null) //$NON-NLS-1$
            + ", панель=" + (part == null ? "не найдена" : part.getClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
            + ", область=" + (scopeSelection == null ? "null" : scopeSelection.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
        if (filters == null || scopeName == null || scopeSelection == null)
        {
            toast("Проверить", "Не удалось определить область отбора панели."); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        if (showAll || "ALL".equals(scopeName) || "SUBSYSTEM_FILTER".equals(scopeName)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            toast("Проверить", //$NON-NLS-1$
                "Для перепроверки выберите область «Текущий проект», «Текущий объект» или «Текущий элемент»."); //$NON-NLS-1$
            return null;
        }

        Map<IProject, Set<EObject>> selectedObjects = nonEmptySelectedObjects(scopeSelection);
        Set<IProject> selectedProjects = selectedProjects(scopeSelection);
        logScope(selectedObjects, selectedProjects);
        if ("CURRENT_PROJECT".equals(scopeName)) //$NON-NLS-1$
        {
            Set<IProject> projects = new LinkedHashSet<>(selectedProjects);
            projects.addAll(selectedObjects.keySet());
            if (projects.isEmpty())
                toast("Проверить", "В текущей области нет проекта для перепроверки."); //$NON-NLS-1$ //$NON-NLS-2$
            for (IProject project : projects)
                ComfortCheckRecompute.recomputeProject(project);
            return null;
        }
        if ("CURRENT_OBJECT".equals(scopeName)) //$NON-NLS-1$
            selectedObjects = topObjects(part, scopeSelection, selectedObjects);
        else if (!"CURRENT_ELEMENT".equals(scopeName)) //$NON-NLS-1$
        {
            toast("Проверить", "Неизвестная область отбора панели: " + scopeName); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        if (selectedObjects.isEmpty())
        {
            toast("Проверить", //$NON-NLS-1$
                "В текущей области отбора панели нет объектов для перепроверки.");
            return null;
        }

        // Корневая «Конфигурация» в области объекта — тоже один объект: проверяется только она,
        // весь проект перепроверяет область «Текущий проект».
        for (Map.Entry<IProject, Set<EObject>> entry : selectedObjects.entrySet())
        {
            ComfortCheckRecompute.recomputeObjects(entry.getKey(), entry.getValue());
        }

        return null;
    }

    private static Object problemFilters(IViewPart part)
    {
        if (part == null)
            return null;
        try
        {
            Class<?> plugin = part.getClass().getClassLoader()
                .loadClass("com._1c.g5.v8.dt.internal.ui.validation.V8UiValidationPlugin"); //$NON-NLS-1$
            return Global.invoke(plugin, "getProblemFilters"); //$NON-NLS-1$
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<IProject, Set<EObject>> topObjects(IViewPart part, Object selection,
        Map<IProject, Set<EObject>> objects)
    {
        Object manager = Global.getField(part, "scopeSelectionManager"); //$NON-NLS-1$
        Map<IProject, Set<EObject>> result = new LinkedHashMap<>();
        for (IProject project : objects.keySet())
        {
            Object top = Global.invoke(manager, "getTopObjects", selection, project); //$NON-NLS-1$
            if (top instanceof Set<?> set && !set.isEmpty())
                result.put(project, (Set<EObject>)set);
        }
        return result;
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
