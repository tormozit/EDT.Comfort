package tormozit;

import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.menus.UIElement;

/**
 * «Отложенные (N)» в панели «Проблемы конфигурации»: пересчитать объекты всех проектов,
 * пересчёт проверок которых отложил шлюз после массового изменения ({@link DeferredCheckContexts}).
 * Подпись и доступность обновляет {@link DeferredCheckContexts} при изменении списка.
 */
public class ProblemViewRecomputeDeferredHandler extends AbstractHandler implements IElementUpdater
{
    static final String COMMAND_ID = "tormozit.problemView.recomputeDeferred"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event)
    {
        DeferredCheckContexts.recomputeAll();
        return null;
    }

    @Override
    public boolean isEnabled()
    {
        return DeferredCheckContexts.hasAny();
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters)
    {
        int count = DeferredCheckContexts.objectCount();
        // Рядом кнопка «Запустить проверки» — слова в подписи не повторяем
        element.setText("Отложенные (" + count + ")");
        element.setTooltip(count > 0
            ? "Запустить проверки отложенных: " + count + " "
                + Global.russianPlural(count, "объект", "объекта", "объектов")
                + ". Открытый в редакторе объект пересчитывается сам." + Global.pluginSignForTooltip()
            : "Отложенного пересчета проверок нет." + Global.pluginSignForTooltip());
    }
}
