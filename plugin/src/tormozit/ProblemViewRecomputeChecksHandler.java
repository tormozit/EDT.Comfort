package tormozit;

import java.util.Collection;
import java.util.LinkedHashMap;
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
 * такой случай не выполняется молча дорогой полной перепроверкой, а честно сообщается в лог:
 * для проверки всего проекта есть штатная команда EDT.
 * <p>
 * Кнопка живёт в собственной панели инструментов панели «Ошибки конфигурации», поэтому у обработчика
 * нет {@code activeWhen} по {@code activePartId}: иначе кнопка сереет, как только фокус уходит из
 * панели. Панель ищется явно через {@link IWorkbenchPage#findView}, а не через
 * {@link HandlerUtil#getActivePart}, который вне фокуса вернёт не тот part.
 */
public class ProblemViewRecomputeChecksHandler extends AbstractHandler
{
    private static final String LOG_TOPIC = "problem-view-commands"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event)
    {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        IViewPart part = page != null ? page.findView(ProblemViewMarkers.PROBLEM_VIEW_ID) : null;
        Object scopeSelection = Global.getField(part, "scopeSelection"); //$NON-NLS-1$
        if (scopeSelection == null)
        {
            Global.tempLog(LOG_TOPIC, "Проверить: у панели нет scopeSelection"); //$NON-NLS-1$
            return null;
        }

        Map<IProject, Set<EObject>> selectedObjects = nonEmptySelectedObjects(scopeSelection);
        Global.tempLog(LOG_TOPIC, "Проверить: область — объектов по проектам: " + describe(selectedObjects)); //$NON-NLS-1$

        if (selectedObjects.isEmpty())
        {
            Global.tempLog(LOG_TOPIC, "Проверить: в области нет конкретных объектов" //$NON-NLS-1$
                + " (область «весь проект» или отбор по подсистемам) — точечная перепроверка невозможна"); //$NON-NLS-1$
            toast("Проверить", //$NON-NLS-1$
                "В текущей области отбора панели нет конкретных объектов."
                + " Выберите область «Текущий объект» или «Текущий элемент»,"
                + " либо запустите штатную проверку проекта.");
            return null;
        }

        for (Map.Entry<IProject, Set<EObject>> entry : selectedObjects.entrySet())
            ComfortCheckRecompute.recomputeObjects(entry.getKey(), entry.getValue());
        return null;
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

    private static String describe(Map<IProject, Set<EObject>> objectsByProject)
    {
        if (objectsByProject.isEmpty())
            return "<пусто>"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<IProject, Set<EObject>> entry : objectsByProject.entrySet())
        {
            IProject project = entry.getKey();
            Collection<EObject> objects = entry.getValue();
            sb.append(sb.length() > 0 ? ", " : "") //$NON-NLS-1$ //$NON-NLS-2$
                .append(project != null ? project.getName() : "?") //$NON-NLS-1$
                .append('=').append(objects != null ? objects.size() : 0);
        }
        return sb.toString();
    }
}
