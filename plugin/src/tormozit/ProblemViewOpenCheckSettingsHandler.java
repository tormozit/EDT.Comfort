package tormozit;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.validation.marker.Marker;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * «Открыть настройку проверки» в панели «Проблемы конфигурации» (тулбар и
 * контекстное меню) — открывает настройку проверки выделенной проблемы на
 * странице «Проверки» параметров проекта, как двойной щелчок в колонке «Код
 * проверки» ({@link ProblemViewHook#openCheckSettings}).
 *
 * <p>Без выделенной проблемы-проверки команда недоступна (серая кнопка) — как
 * у штатной «Открыть проверку...» EDT ({@code OpenCheckDescriptionHandler}):
 * тот же признак — маркер адаптируется из выделения, а его короткий код
 * резолвится в {@link CheckUid} через {@link ICheckRepository}.
 */
public class ProblemViewOpenCheckSettingsHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        Marker marker = ProblemViewMarkers.firstSelectedMarker(part);
        if (marker == null)
            return null;
        Shell shell = HandlerUtil.getActiveShell(event);
        ProblemViewHook.openCheckSettings(shell, marker);
        return null;
    }

    @Override
    public void setEnabled(Object evaluationContext)
    {
        setBaseEnabled(resolveCheckMarker(evaluationContext) != null);
    }

    /** Первый маркер выделения, для которого есть настройка проверки, либо {@code null}. */
    private static Marker resolveCheckMarker(Object evaluationContext)
    {
        if (!(evaluationContext instanceof IEvaluationContext context))
            return null;
        Object selection = context.getDefaultVariable();
        if (!(selection instanceof List<?> list) || list.isEmpty())
            return null;
        Marker marker = Adapters.adapt(list.get(0), Marker.class);
        if (marker == null || marker.getProject() == null)
            return null;
        String checkId = marker.getCheckId();
        if (checkId == null || checkId.isBlank())
            return null;
        ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
        if (repository == null)
            return marker;
        try
        {
            return repository.getUidForShortUid(checkId, marker.getProject()) != null ? marker : null;
        }
        catch (RuntimeException e)
        {
            return marker;
        }
    }
}
