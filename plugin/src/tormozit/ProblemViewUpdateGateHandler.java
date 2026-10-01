package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/**
 * Тумблер тулбара панели «Проблемы конфигурации»: заслонка обновлений списка.
 * Нажат — события объединяются; чужие изменения маркеров отсекаются только в
 * областях, где можно надёжно определить принадлежность объектов. При «Показывать
 * все» принадлежность не проверяется. Снят — как в EDT.
 */
public class ProblemViewUpdateGateHandler extends AbstractHandler
{
    static final String COMMAND_ID = "tormozit.problemView.updateGate"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event)
    {
        boolean enabled = !ComfortSettings.isProblemViewUpdateGateEnabled();
        ProblemViewHook.applyUpdateGateEnabled(enabled);
        return null;
    }
}
