package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/**
 * «Отобрать по значению ячейки» (Alt+W по умолчанию) для таблиц плагина с
 * {@link FormTableInteraction}. Повторный вызов на той же ячейке снимает отбор.
 *
 * <p>Отдельный класс, а не вложенный: точка входа из {@code plugin.xml}. Цель определяется по
 * текущему фокусу — команда общая на все таблицы, отдельной команды на каждую панель не нужно.
 */
public final class FormTableFilterHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        FormTableInteraction.toggleFilterOnFocusedTable();
        return null;
    }
}
