package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/**
 * «Значения колонки» (Alt+F по умолчанию) для таблиц плагина с {@link FormTableInteraction} и
 * деревьев с {@link TreeColumnValueFilterSupport}.
 *
 * <p>Отдельный класс, а не вложенный: точка входа из {@code plugin.xml}. Цель определяется по
 * текущему фокусу — команда общая на все таблицы/деревья, отдельной команды на каждую панель не
 * нужно.
 */
public final class FormTableColumnValuesHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        FormTableInteraction.openColumnValuesOnFocusedTable();
        TreeColumnValueFilterSupport.openColumnValuesOnFocusedTree();
        return null;
    }
}
