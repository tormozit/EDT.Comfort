package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Штатная «Запустить проверку» на выделенных проектах: то же {@code ICheckScheduler.scheduleValidation},
 * но в фоновом задании ({@link ComfortCheckRecompute#recomputeProject}). Штатный обработчик
 * ({@code ScheduleValidationHandler}) назначает проверки всех объектов в потоке UI и держит окно
 * EDT десятки секунд. На отдельных объектах команда остаётся штатной: там назначение быстрое.
 */
public class StartCheckProjectsHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        if (HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection)
            for (Object element : selection)
                if (element instanceof IProject project)
                    ComfortCheckRecompute.recomputeProject(project);
        return null;
    }
}
